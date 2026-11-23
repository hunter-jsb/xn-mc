# Stasis destinations and timing

The existing built chamber is **honeyfill compact**, region 1 of morgant1c's
[relog porter schematic](https://www.reddit.com/r/technicalminecraft/comments/1nqbre2/more_relog_porter_designs/).
The imported region is 7 × 5 × 5. Coordinates below are local to its minimum corner;
they are not world coordinates. Preserve the working chamber while prototyping the next one.

## Trace of the existing circuit

The tripwire runs between (2,3,1) and (2,3,3), with string at (2,3,2).
Both hooks feed a comparator loop. Minecraft block-state `facing` is the comparator's
**input** direction; the output is on its opposite side.

| Part | Local coordinates | Signal path |
| --- | --- | --- |
| Fast fader | comparators (4,3,0), (4,3,1) | Output east along Z0, then west along Z1. Two comparators, two dust losses per circuit |
| Slow fader | comparators (4,3,3), (5,3,3), (5,3,4), (4,3,4) | Output east along Z3, through quartz (6,3,3), then west along Z4. Four comparators, one dust loss per circuit |
| Slow-fader inverter | wall torch (6,3,2) | Attached to quartz (6,3,3); when slow fader expires, injects power into the fast loop's right side |
| Piston inverter | wall torch (5,2,2) | Attached to quartz (5,2,1), below fast-loop dust (5,3,1) |
| Piston drive | dust (5,1,2), (4,1,2), quartz (3,1,2) | Drives west-facing piston at (3,2,2), beside the pearl column |

While the pearl holds the string, both loops are refreshed. When the string releases,
both decay. The fast fader expires first, enabling the piston inverter and extending
the piston. Later, the slow fader expires, turning on its inverter; that repowers the
fast loop, switches off the piston inverter, and retracts the piston.

This explains the **finite interval**, rather than a simple countdown that fires forever.
The piston is in the pearl's way during that interval. Returning too late finds it
retracted again.

## Calculated starting estimate, not measured cutoffs

Minecraft 26.3's comparator update delay is 2 game ticks. With the geometry above,
an idealized, fully charged circuit gives approximately:

| Transition | Estimate after the tripwire hooks release | At a steady 20 TPS |
| --- | --- | --- |
| Fast-side drive runs out; piston inverter turns on | 28 game ticks | 1.4 seconds |
| Slow-side inverter repowers fast side; piston inverter turns off | 120 game ticks | 6 seconds |

Derivation: the fast loop starts at outputs 14 and 13 when powered from the hook.
Each 2-tick comparator update alternately reduces the outputs by two. The comparator
feeding the right-hand dust reaches zero at 26 ticks; the piston inverter adds two.
The slow loop starts at 15 throughout. One strength is lost per 8-tick circulation;
the comparator feeding the quartz reaches zero at 116 ticks, followed by two 2-tick
torch updates to restore the holding state.

These estimates exclude tripwire release polling, piston motion, pearl collision/order,
login processing, the phase of scheduled ticks, and any JAH redstone changes. In particular,
**time since hook release is not time since clicking Disconnect**. Saved block power in a
schematic is a snapshot, not evidence of its live state. Existing observations are a
successful recall after about 2 seconds and no recall after 15 seconds. They don't
establish exact boundaries. No calculated estimate is automatically enabled as a profile.

## Expanding to more chambers

Identical copies can all recall at once. Give each chamber its own non-overlapping
**piston-extended interval**. The fast loop controls the opening edge and the slow loop
controls the closing edge. Moving only the closing edge produces overlapping intervals.

For a later destination, lengthen/rework both faders, preserving each return path's signal
loss and compare-mode settings. Adding dust can shorten a timer by increasing attenuation;
adding comparators increases circulation time. Comparator counts alone aren't enough.
Prototype this as a second circuit with a visible piston output before committing pearls.
Keep the chamber independently ticking throughout the logout; a pearl is not a substitute
for a verified loader while its owner is offline. Physical redstone can share a proven
loader only if all affected chunks remain ticking. A mod destination does not load them.

Start with the existing chamber as **home** and a second destination kept as a draft.
Choose the second interval only after measuring home's closing edge. Leave substantial
separation, rather than adjoining windows. No exact block-by-block expansion is verified yet.

## XNMC controller

`/stasis add home` creates a draft tied to the current server. `;` → **Stasis** provides
destination selection and editing. Names use letters, digits, hyphens and underscores.
Windows are stored in `config/xnmc-stasis.json`; they do not imply armed pearls.

- `/stasis test 2` intentionally disconnects and makes **one** reconnect attempt after
  a 2-second client wait. This is a calibration action and can trigger any armed chamber.
- After checking what happened, `/stasis result recalled`, `/stasis result missed`, or
  `/stasis result wrong-destination` records your observation against that trial.
- Repeat around each estimated boundary from a controlled spot. Begin with only the
  chamber under test armed. Record successful waits across several attempts and conditions;
  don't promote one success to a broad working window.
- `/stasis window home <min-seconds> <max-seconds>` saves the reliable **client wait** range.
  This range includes the usual connection overhead of your measurements; it is not a
  conversion of server ticks to seconds. Choose a range comfortably inside observed edges.
- `/stasis recall home` targets the midpoint of that saved range.
- `/stasis list`, `/stasis remove <name>`, and `/stasis cancel` manage the saved destinations
  and current countdown. Cancel is also on the disconnect screen. Manually leaving that
  screen cancels the timer; manual reconnects are never blocked.

Windows need at least a 0.5-second width and **more than one second between destinations**.
This is a configuration minimum, not a guarantee under latency or server lag. Wider margins
are desirable. The countdown measures monotonic local time from Fabric's disconnect event
to starting the connection. Login/authentication and server processing happen **after** that.

Trial events are saved locally in `.minecraft/xnmc/stasis-trials.jsonl`: requested wait,
actual wait, disconnect-to-join time, and your explicit result. Joining alone is never
classified as a successful teleport. No records are uploaded. Rejoining position, when
available, is only an observation at join, not proof of the final destination.

Intentional recalls turn off AFK Guard's retries and Meteor AutoReconnect, if enabled.
They remain off afterward: re-arm AFK Guard manually once situated. If the Meteor adapter
can't verify/control AutoReconnect, the recall doesn't start. Other independent reconnect
mods must also be off. A damage logout or unrelated kick does not start a stasis timer.
Unknown disconnect reasons, a missed deadline, a failed connection, and leaving the
disconnect screen cancel rather than retry. A reconnect already sent can't be undone by
cancelling a countdown; use the normal connection screen's Cancel button if still connecting.
No pending action survives a game restart. No live recall has been initiated by development
or automated tests.
