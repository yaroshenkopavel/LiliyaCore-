from __future__ import annotations

import sys
from dataclasses import dataclass

import torch
import torch.nn.functional as F
from transformers import AutoModel, AutoTokenizer

MODEL_ID = "intfloat/multilingual-e5-small"
MODEL_REVISION = "fd1525a9fd15316a2d503bf26ab031a61d056e98"
EXPECTED_DIMENSION = 384
MAX_TOKENS = 512


@dataclass(frozen=True)
class RankingCase:
    name: str
    query: str
    relevant: str
    distractor: str


GATING_CASES = (
    RankingCase(
        "same-language-en",
        "query: where did I leave my keys?",
        "passage: I left the keys on the kitchen table.",
        "passage: Whales migrate through the ocean.",
    ),
    RankingCase(
        "same-language-ru",
        "query: где я оставил ключи?",
        "passage: Я оставил ключи на кухонном столе.",
        "passage: Киты мигрируют через океан.",
    ),
    RankingCase(
        "same-language-ua",
        "query: де я залишив ключі?",
        "passage: Я залишив ключі на кухонному столі.",
        "passage: Кити мігрують через океан.",
    ),
    RankingCase(
        "cross-ru-ua",
        "query: где я оставил ключи от квартиры?",
        "passage: Після вечері ключі від квартири залишилися на кухонному столі.",
        "passage: Сьогодні біля узбережжя спостерігали міграцію китів.",
    ),
    RankingCase(
        "cross-ua-ru",
        "query: де лежать ключі від квартири?",
        "passage: После ужина ключи от квартиры остались на кухонном столе.",
        "passage: Сегодня у побережья наблюдали миграцию китов.",
    ),
    RankingCase(
        "cross-en-ru",
        "query: where are the apartment keys?",
        "passage: После ужина ключи от квартиры остались на кухонном столе.",
        "passage: Сегодня у побережья наблюдали миграцию китов.",
    ),
    RankingCase(
        "cross-ru-en",
        "query: где лежат ключи от квартиры?",
        "passage: After dinner, the apartment keys were left on the kitchen table.",
        "passage: Whales migrate long distances through the ocean each year.",
    ),
    RankingCase(
        "lexical-overlap-route-ru",
        "query: как утром добраться до железнодорожного вокзала?",
        "passage: Утром до железнодорожного вокзала удобнее всего доехать на автобусе номер двенадцать.",
        "passage: Железнодорожный вокзал утром изображён на фотографии в туристическом буклете.",
    ),
    RankingCase(
        "lexical-overlap-passport-ru",
        "query: где хранится мой паспорт?",
        "passage: Мой паспорт хранится дома в синей папке в верхнем ящике письменного стола.",
        "passage: В моём паспорте указано место рождения и дата выдачи документа.",
    ),
    RankingCase(
        "paraphrase-ru",
        "query: как мне добраться до железнодорожного вокзала утром?",
        "passage: Утром до станции поездов удобнее всего доехать на автобусе номер двенадцать.",
        "passage: Утром я обычно завариваю кофе и открываю окно на кухне.",
    ),
    RankingCase(
        "paraphrase-ua",
        "query: як вранці дістатися до залізничного вокзалу?",
        "passage: До станції поїздів у ранковий час найзручніше їхати дванадцятим автобусом.",
        "passage: Вранці я зазвичай готую каву та відчиняю кухонне вікно.",
    ),
)

# This deliberately difficult polysemy case is retained as evidence of a full-precision
# upstream model limitation discovered during acceptance work. It is diagnostic only and is not
# used to pretend that a failed full-precision ranking is a Q8 regression or a production pass.
KNOWN_REFERENCE_LIMITS = (
    RankingCase(
        "known-limit-keys-vs-encryption-ru",
        "query: где лежат ключи от квартиры?",
        "passage: После ужина брелок с ключами от квартиры оставили на кухонном столе.",
        "passage: В руководстве перечислены ключи шифрования для защиты архивов квартиры.",
    ),
)


def mean_pool(last_hidden_state: torch.Tensor, attention_mask: torch.Tensor) -> torch.Tensor:
    mask = attention_mask.unsqueeze(-1).to(last_hidden_state.dtype)
    summed = (last_hidden_state * mask).sum(dim=1)
    counts = mask.sum(dim=1).clamp_min(1.0)
    return summed / counts


def ranks_relevant_higher(model, tokenizer, case: RankingCase) -> tuple[bool, str | None]:
    texts = (case.query, case.relevant, case.distractor)
    encoded = tokenizer(
        texts,
        padding=True,
        truncation=False,
        return_tensors="pt",
    )
    token_lengths = encoded["attention_mask"].sum(dim=1)
    if int(token_lengths.max().item()) > MAX_TOKENS:
        return False, "TOKEN_BOUND_FAILURE"

    outputs = model(**encoded)
    vectors = mean_pool(outputs.last_hidden_state, encoded["attention_mask"])
    if vectors.shape != (3, EXPECTED_DIMENSION):
        return False, "DIMENSION_FAILURE"
    vectors = F.normalize(vectors, p=2, dim=1)
    if not torch.isfinite(vectors).all():
        return False, "NON_FINITE_FAILURE"

    relevant_score = torch.dot(vectors[0], vectors[1]).item()
    distractor_score = torch.dot(vectors[0], vectors[2]).item()
    return relevant_score > distractor_score, None


def main() -> int:
    torch.manual_seed(0)
    tokenizer = AutoTokenizer.from_pretrained(
        MODEL_ID,
        revision=MODEL_REVISION,
        trust_remote_code=False,
    )
    model = AutoModel.from_pretrained(
        MODEL_ID,
        revision=MODEL_REVISION,
        trust_remote_code=False,
        use_safetensors=True,
    )
    model.eval()

    failures: list[str] = []
    with torch.no_grad():
        for case in GATING_CASES:
            passed, structural_failure = ranks_relevant_higher(model, tokenizer, case)
            if structural_failure is not None:
                print(f"reference case {case.name}: {structural_failure}")
                failures.append(case.name)
            elif passed:
                print(f"reference case {case.name}: PASS")
            else:
                print(f"reference case {case.name}: RANKING_FAILURE")
                failures.append(case.name)

        for case in KNOWN_REFERENCE_LIMITS:
            passed, structural_failure = ranks_relevant_higher(model, tokenizer, case)
            if structural_failure is not None:
                print(f"reference known-limit {case.name}: {structural_failure}")
            elif passed:
                print(f"reference known-limit {case.name}: UNEXPECTED_PASS")
            else:
                print(f"reference known-limit {case.name}: OBSERVED_REFERENCE_LIMIT")

    if failures:
        print("trusted reference quality: FAILED cases=" + ",".join(failures))
        return 1

    print("trusted reference quality: PASS")
    return 0


if __name__ == "__main__":
    sys.exit(main())
