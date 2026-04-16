# Strategy Squad

Production-grade, microservice-based algorithmic trading platform for Nifty and Bank Nifty options.

## Core Trading Approach
- Statistical arbitrage
- Mean reversion
- Realized volatility only (no implied volatility (IV), option Greeks, or volatility index (VIX) dependencies)
- 15-minute historical buckets

## Architecture Constraints (Non-Negotiable)
- Java 21+
- Virtual Threads required
- Redis Streams for inter-service communication
- QuestDB for time-series storage
- Redis for cache/state
- Docker-first deployment
- AWS ap-south-1 target
- Stateless services only

## Critical System Rules
### Market Data
- Only `feed-service` connects to Kite
- All other services consume via Redis

### Trading Flow
No order can reach `execution-service` without:
1. `strategy-engine` intent
2. `risk-guardian` approval

### Risk/Safety
- `risk-guardian` is final authority
- Daily drawdown kill switch required
- Every trade must be traceable
- Every execution must map to the canonical event chain:
  - `trade_intent_v1`
  - `risk_decision_v1`
  - `execution_update_v1`

## Service Boundaries
Service ownership is independent and must not be crossed:
- `feed-service-agent`
- `analytic-vault-agent`
- `arb-sentinel-agent`
- `strategy-engine-agent`
- `risk-guardian-agent`
- `execution-service-agent`
- `dashboard-ui-agent`
- `qa-agent`
- `devops-agent`

## Mandatory Development Workflow
Before implementation, define:
1. Purpose
2. Scope
3. Dependencies
4. Inputs/outputs
5. Event contracts
6. Failure modes
7. Test plan
8. Observability

## Event Contract Policy
- Events must be versioned
- Events must be documented
- Events must have fixed schemas
- Schemas must never change silently

Canonical event names:
- `normalized_tick_v1`
- `mispricing_signal_v1`
- `trade_intent_v1`
- `risk_decision_v1`
- `execution_update_v1`

## Service Design Requirements
Each service must provide:
- Health endpoint
- Structured logging
- Metrics
- Environment-based configuration
- Retry policies
- Idempotent processing
- Integration tests

## Testing Requirements
Minimum required test coverage:
- Unit tests
- Contract tests
- Integration tests (Redis + DB)
- Replay tests (historical data)
- Failure-path tests
- Latency benchmarks

## Observability Requirements
Each service must expose:
- Request count
- Latency
- Error rate
- Stream lag
- Domain-specific metrics

## Coding Standards
- Clean architecture
- Immutable DTOs preferred
- Explicit interfaces
- No hardcoded credentials
- No hidden threads
- Deterministic behavior

## Definition of Done
A task is complete only if:
- Contracts are defined
- Code compiles
- Tests pass
- Logs and metrics exist
- Failure paths are handled
- Service boundaries are respected
