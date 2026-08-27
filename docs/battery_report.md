# Battery report

Opcode `0x0004`, sent by the accessory. Reports the charge level of every battery
the accessory has.

```
04 00 04 00 04 00 [count] [entry] ... [entry]
```

`count` is the number of battery components that follow, and each entry is five
bytes, so the total packet length is `7 + 5 * count`.

## Entry

```
[component] 01 [level] [status] 01
```

| Field       | Notes                                                          |
| ----------- | -------------------------------------------------------------- |
| `component` | Which battery this is, see below                                 |
| `level`     | Charge percentage, `0x00`-`0x64`. `0xFF` means unknown           |
| `status`    | `0x01` charging, `0x02` not charging, `0x04` disconnected, `0x05` optimized charging |

The second and fifth bytes of an entry have only ever been observed as `0x01`.

### Components

| Value  | Component                    |
| ------ | ---------------------------- |
| `0x01` | Headset (single, over-ear)   |
| `0x02` | Right bud                    |
| `0x04` | Left bud                     |
| `0x08` | Case                         |

## Count varies per model

**`count` is not fixed at 3.** Earbuds with a charging case report three
components (left, right, case), but AirPods Max are a single piece with no case
and report exactly one `0x01` (headset) component. A parser that assumes a
22-byte packet, or that looks only for the left/right/case components, silently
drops every battery report an AirPods Max sends.

The order of the entries is meaningful for earbuds: the first bud in the packet
is the primary one, i.e. the one currently acting as the link to the host.

### Examples

AirPods Pro 2, both buds and the case (22 bytes):

```
04 00 04 00 04 00 03  04 01 64 02 01  02 01 5A 02 01  08 01 46 02 01
                   ^  ^^ left, 100%   ^^ right, 90%   ^^ case, 70%
                   count = 3
```

AirPods Max (12 bytes):

```
04 00 04 00 04 00 01  01 01 55 02 01
                   ^  ^^ headset, 85%
                   count = 1
```
