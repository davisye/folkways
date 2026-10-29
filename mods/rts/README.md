# Folkways RTS

Manual selection, commands and actual squads use Core's single body runner.
Vanilla owns equipment, native weapon selection, hostility and autonomous defence.

## Recoverable command failures

- A designated attack retains its original order, target and 600-tick deadline
  after a temporary `NO_WAY_THERE` or `GOODS_GONE` refusal. Only that member's
  execution step is revoked; retries back off from 10 to at most 100 ticks.
  The other members continue attacking with their existing order objects.
- Replacing the command, releasing control, losing authority or losing the
  target prevents an old attempt from returning. Retrying never renews the
  attack deadline. Equipment remains under the ordinary hand-loan lifecycle.
- Regrouping preserves healthy HOLD handles and slots. Failed, missing or
  reloaded members receive fresh local slots through the existing planner.
  Temporary failure to find an initial anchor also remains recoverable.
- After the 1200-tick active regroup budget, failure remains observable while
  recovery probes continue at a low frequency. They create HOLD orders only;
  an ended attack cannot restart. All loaded members still need 20 stable ticks
  together before regrouping completes.

The existing reference-member / virtual-anchor movement planner is retained.
This recovery change does not establish a new formation algorithm or large-battle
performance support. GameTests cover temporary obstruction, physical damage and
target defeat, healthy-handle preservation and replacement/release during backoff.
The Epic Fight pursuit spec additionally exercises real pointer commands against
spiders on uneven ground when the optional integration is installed.
