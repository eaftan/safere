### stack

| Input | Length | Operation | text ratio | mixed ratio | rare ratio | reject ratio |
|---|---:|---|---:|---:|---:|---:|
| safere-string | 512 | matches | 0.632× | 1.104× | 4.429× | 5.012× |
| safere-string | 512 | lookingAt | 0.637× | 1.113× | 4.400× | 4.965× |
| safere-string | 512 | find | 0.599× | 1.113× | 4.186× | 4.993× |
| safere-string | 1024 | matches | 0.559× | 0.965× | 4.063× | 4.264× |
| safere-string | 1024 | lookingAt | 0.550× | 0.939× | 4.177× | 4.730× |
| safere-string | 1024 | find | 0.557× | 0.952× | 4.154× | 4.667× |
| safere-utf8 | 512 | matches | 0.532× | 0.921× | 3.466× | 4.707× |
| safere-utf8 | 512 | lookingAt | 0.538× | 0.899× | 4.225× | 4.749× |
| safere-utf8 | 512 | find | 0.537× | 0.925× | 4.291× | 4.782× |
| safere-utf8 | 1024 | matches | 0.569× | 0.999× | 4.258× | 4.811× |
| safere-utf8 | 1024 | lookingAt | 0.575× | 1.018× | 4.228× | 4.833× |
| safere-utf8 | 1024 | find | 0.576× | 1.006× | 4.301× | 4.857× |

### onePass

| Input | Length | Operation | text ratio | reject ratio |
|---|---:|---|---:|---:|
| safere-string | 512 | matches | 1.000× | 1.000× |
| safere-string | 512 | lookingAt | 0.988× | 1.009× |
| safere-string | 512 | find | 1.001× | 1.002× |
| safere-string | 1024 | matches | 1.002× | 1.090× |
| safere-string | 1024 | lookingAt | 0.995× | 0.977× |
| safere-string | 1024 | find | 1.003× | 0.915× |
| safere-utf8 | 512 | matches | 0.999× | 1.000× |
| safere-utf8 | 512 | lookingAt | 1.015× | 1.000× |
| safere-utf8 | 512 | find | 1.000× | 1.001× |
| safere-utf8 | 1024 | matches | 1.127× | 0.885× |
| safere-utf8 | 1024 | lookingAt | 0.998× | 0.998× |
| safere-utf8 | 1024 | find | 0.996× | 0.999× |

### Existing 512 boundary, candidate implementation

| Input | Operation | Profile | 511 ns/doc | 512 ns/doc | 513 ns/doc |
|---|---|---|---:|---:|---:|
| safere-string | matches | text | 1269.4 | 1253.2 | 2004.4 |
| safere-string | matches | reject | 3957.1 | 4534.5 | 773.8 |
| safere-string | lookingAt | text | 1272.3 | 1264.5 | 2027.2 |
| safere-string | lookingAt | reject | 3829.0 | 3381.7 | 765.7 |
| safere-string | find | text | 1260.9 | 1267.8 | 2051.2 |
| safere-string | find | reject | 3347.6 | 3973.6 | 759.8 |
| safere-utf8 | matches | text | 1107.9 | 1103.8 | 2096.6 |
| safere-utf8 | matches | reject | 3789.9 | 3930.1 | 814.3 |
| safere-utf8 | lookingAt | text | 1111.9 | 1104.1 | 2094.7 |
| safere-utf8 | lookingAt | reject | 3763.5 | 3830.0 | 806.5 |
| safere-utf8 | find | text | 1121.6 | 1129.3 | 2110.2 |
| safere-utf8 | find | reject | 3365.6 | 3950.0 | 812.1 |
