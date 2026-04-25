# Strategy Run Report

## Run Metadata
- **Run id**: run-sample-001
- **Session id**: session-sample-001
- **Mode**: simulation
- **Underlying**: NIFTY
- **Strategy name**: Short Straddle
- **Start time**: 2026-04-24 09:15:00 IST
- **End time**: 2026-04-24 09:31:45 IST
- **Total duration**: 16m 45s
- **Initial max lots**: 20
- **Lot size**: 65

## Initial Structure
| Leg Id | Label | Side | Type | Strike | Expiry | Entry Price | Initial Qty | Initial Delta |
| --- | --- | --- | --- | ---: | --- | ---: | ---: | ---: |
| leg-call | ATM call | SHORT | CE | 24300 | 30 Apr 2026 | 182.4 | 130 | 0.42 |
| leg-put | ATM put | SHORT | PE | 24300 | 30 Apr 2026 | 178.1 | 130 | -0.39 |

## Adjustment Timeline
| Timestamp | Action | Leg | Strike | Old Qty | New Qty | Lots Before | Lots After | Trigger | Reason Code |
| --- | --- | --- | ---: | ---: | ---: | ---: | ---: | --- | --- |
| 2026-04-24 09:20:00 IST | REDUCE | ATM call | 24300 | 130 | 65 | 4 | 3 | HARD | critical_net_delta |
| 2026-04-24 09:27:00 IST | ADD | ATM put | 24250 | 0 | 65 | 3 | 4 | NORMAL | theta_preserving_rebalance |

### 2026-04-24 09:20:00 IST - REDUCE
- **Selected leg**: ATM call / CE / SHORT / 24300
- **Reason**: critical_net_delta
- **Explanation**: Reduced one short call lot to improve portfolio neutrality.

### 2026-04-24 09:27:00 IST - ADD
- **Selected leg**: ATM put / PE / SHORT / 24250
- **Reason**: theta_preserving_rebalance
- **Explanation**: Added one short put lot to improve neutrality while preserving carry.

## Signal Snapshot Per Adjustment
### 2026-04-24 09:20:00 IST - REDUCE
- **Net delta before**: 22.9
- **Post-action net delta**: 10.2
- **Delta improvement**: 12.7
- **Underlying direction**: UP
- **Profit alignment**: UNFAVORABLE
- **Live PnL slope**: 2m -28.5 / 5m -46.25
- **Volume confirmation**: Confirmed
- **Theta score**: -0.35
- **Liquidity score**: 0.82
- **Candidate score**: 1.48
- **Churn guard status**: Clear

### 2026-04-24 09:27:00 IST - ADD
- **Net delta before**: 10.2
- **Post-action net delta**: 4.95
- **Delta improvement**: 5.25
- **Underlying direction**: DOWN
- **Profit alignment**: FAVORABLE
- **Live PnL slope**: 2m 18.75 / 5m 26
- **Volume confirmation**: Confirmed
- **Theta score**: 0.72
- **Liquidity score**: 0.88
- **Candidate score**: 1.61
- **Churn guard status**: Clear

## PnL Summary
- **Booked PnL**: 875 Rs
- **Live / unrealized PnL**: 1225 Rs
- **Total PnL**: 2100 Rs

| Leg | Booked PnL | Live PnL | Total PnL |
| --- | ---: | ---: | ---: |
| ATM call | 420 Rs | 409.5 Rs | 829.5 Rs |
| ATM put | 455 Rs | 815.5 Rs | 1270.5 Rs |

| Timestamp | Action | Leg | Booked PnL Impact |
| --- | --- | --- | ---: |
| 2026-04-24 09:20:00 IST | REDUCE | ATM call | 420 Rs |
| 2026-04-24 09:27:00 IST | ADD | ATM put | 0 Rs |

## Final Structure
| Leg Id | Label | Side | Type | Strike | Expiry | Entry Price | Final Open Qty | Booked PnL | Live PnL | Total PnL | Status |
| --- | --- | --- | --- | ---: | --- | ---: | ---: | ---: | ---: | ---: | --- |
| leg-call | ATM call | SHORT | CE | 24300 | 30 Apr 2026 | 182.4 | 65 | 420 Rs | 409.5 Rs | 829.5 Rs | PARTIALLY_EXITED |
| leg-put-added | ATM put | SHORT | PE | 24250 | 30 Apr 2026 | 169.8 | 65 | 455 Rs | 815.5 Rs | 1270.5 Rs | OPEN |

## Adjustment Decision Summary
- **Total ADD actions**: 1
- **Total REDUCE actions**: 1
- **Total manual exits**: 0
- **Skipped adjustments by reason**: None
- **Delayed adjustments**: 0
- **Hard triggers**: 1
- **Normal triggers**: 1
- **Churn guard blocks**: 0

## Observations
- Net delta improved over the recorded adjustment sequence.
- Total open quantity stayed within the configured max lot cap.
- No recorded action worsened absolute net delta.
- No skips were attributed to missing live data.
- Cooldown and churn guard protections did not block any recorded action.
