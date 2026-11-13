| Category | String / RE2 | String / Rust | UTF-8 / RE2 | UTF-8 / Rust |
|---|---:|---:|---:|---:|
| Real-world find | 0.412 (142) | 0.872 (142) | 0.500 (142) | 1.060 (142) |
| Real-world find: match | 0.886 (71) | 1.794 (71) | 0.956 (71) | 1.936 (71) |
| Real-world find: no match | 0.191 (71) | 0.424 (71) | 0.262 (71) | 0.580 (71) |
| Real-world replaceAll | 0.523 (151) | 0.880 (151) | 0.825 (151) | 1.387 (151) |
| All declared find | 0.497 (449) | 1.284 (455) | 0.466 (477) | 1.244 (483) |
| Steady-state compile | 0.645 (15) | 0.352 (15) | — | — |
| First compile | 752.698 (11) | 349.830 (14) | — | — |
| Real-world find excluding mapFieldPath | 0.379 (136) | 0.823 (136) | 0.466 (136) | 1.013 (136) |
