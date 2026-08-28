# Future Cognitive Core Architecture Note — 2026-08-29

Status: **FUTURE ARCHITECTURE NOTE**. This document records useful design directions only. It does not change the current roadmap, frozen subsystem semantics, or the scope of the active Reflection / Learning Foundation v0.1 work.

## Why this note exists

A comparative cognitive-core architecture review highlighted several ideas worth preserving for later LiliyaCore stages. They fit the existing foundation-first design, but should not be implemented prematurely while lower-level ownership, reflection, learning, planning, and autonomy contracts are still being established.

## Future cognitive cycle

A useful long-term orchestration model is:

`Interaction / Perception → CognitiveContext → Governor → Retrieval → Reasoning / Planning → Decision → Authority → Execution → Result → Evaluation → Reflection → Learning Candidate → Controlled Consolidation`

Cross-cutting inputs should remain explicit rather than hidden inside one prompt:

`Self + Personality + Trust + Safety + Resource Policy`

This is a future orchestration model, not a current implementation claim.

## Cognitive Governor

Future architecture should include a deterministic governor that owns cognitive-cycle control rather than delegating control of the whole agent loop to an LLM.

Potential responsibilities:

- choose whether a request needs a fast deterministic path or deliberative reasoning;
- enforce iteration, time, token, tool-call, and execution budgets;
- detect repeated states / loops and terminate or escalate safely;
- gate transitions into planning, execution, evaluation, reflection, and consolidation;
- keep LLM output advisory until validated by explicit policy/authority boundaries.

A finite state machine alone is not considered sufficient loop protection. Budgeting, cancellation, deadline, repeated-state detection, and explicit failure/escalation states may be required.

## Context Assembler / CognitiveContext

A dedicated future context-assembly boundary should gather only the material needed for the current cognitive step instead of feeding an unbounded conversation/history directly to the model.

Potential inputs:

- current request / interaction;
- working context;
- relevant Memory records;
- relevant Knowledge items;
- current Self reference;
- applicable Personality profile data;
- relevant Trust context;
- available capabilities / authority context;
- resource constraints and inference budget.

The assembler should own token/context budgeting and relevance selection. Retrieval mechanisms such as embeddings or vector indexes are implementation tools, not semantic memory categories by themselves.

## Fast path and deliberative path

The long-term design should distinguish two execution modes without treating the labels as literal psychological claims:

- **Fast path** — deterministic rules, state transitions, simple intent classification, cached decisions, lightweight models, and simple capability routing;
- **Deliberative path** — heavier LLM reasoning, decomposition, planning, synthesis, and complex evaluation.

The Governor should decide which path is appropriate. Simple requests should not require full expensive model reasoning.

## Resource Governor

For local-first Android operation, cognitive depth should eventually be resource-aware.

Potential inputs:

- battery level / charging state;
- thermal pressure;
- available RAM;
- model availability;
- latency budget;
- foreground/background state;
- user-requested quality/depth policy.

Possible future policies include limiting deliberation under low-resource conditions and allowing deeper planning/reflection when resources permit. Resource state must not silently change Authority or Safety semantics.

## Memory architecture implications

The existing Memory and Knowledge foundations should not be conflated with retrieval technology. Future architecture may distinguish:

- Working Context — transient active cognitive state;
- Episodic Memory — explicit events/experiences with context and provenance;
- Semantic Knowledge — explicit knowledge records;
- Procedural / Skill Memory — learned or declared procedures for accomplishing tasks.

Procedural/Skill memory must remain distinct from Capability and Authority: knowing how to perform a task does not imply permission to perform it.

## Reflection and learning implications

The current Reflection-first approach remains intentional. Future learning should not be implemented as unrestricted LLM self-mutation.

Preferred conceptual pipeline:

`ReflectionRecord → Evaluation → LearningCandidate → LearningPolicy → Controlled Consolidation`

Any later Memory, Knowledge, Personality, Skill, or policy mutation should require explicit ownership, validation, provenance, and rollback/audit semantics appropriate to that subsystem.

## Safety and truth boundaries

Structured-output validation can prove format conformance, not factual truth. Future architecture should continue to keep these concepts separate:

- provenance / origin;
- trust;
- authority;
- confidence;
- truth / verification;
- execution permission.

No single LLM response, embedding similarity score, or schema-valid JSON should automatically collapse these boundaries.

## Deferred nature of this note

This document is intentionally non-binding. It records candidate future architecture for the stages after the required foundations exist. It must not be used to justify premature implementation of:

- global cognitive orchestrators;
- autonomous self-modification;
- unrestricted learning;
- active personality mutation;
- planning/agent loops before their stage;
- Android-specific coupling inside current core foundations.

Any implementation of these ideas must go through the normal feature branch → PR → exact-head Core CI → architecture/security audit → exact-head merge workflow.
