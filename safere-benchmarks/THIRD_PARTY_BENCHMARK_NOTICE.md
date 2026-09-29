# Third-party benchmark input

The `rebar.boundedRepeatContext.rustSource` input in `benchmark-data.json` is the
exact 7,384,531-byte UTF-8 haystack used by rebar's
`curated/10-bounded-repeat/context` benchmark. Its SHA-256 is
`7d43cc8dfd053b083b809bd7ce7d4a074f2fd24a6b7ec38908b3966f3324fa36`.
The haystack is checked in at `data/rust-src-tools-3b0d4813.txt`.
Its `file` recipe in `benchmark-data.json` pins the SHA-256 above, and the
materializer copies its exact bytes to the generated benchmark corpus.

Source: [rebar's haystack](https://github.com/BurntSushi/rebar/blob/463d00f31887e84c38467805b9e3122c314b9521/benchmarks/haystacks/rust-src-tools-3b0d4813.txt),
assembled from [`rust-lang/rust` source at commit `3b0d4813ab461ec81eab8980bb884691c97c5a35`](https://github.com/rust-lang/rust/tree/3b0d4813ab461ec81eab8980bb884691c97c5a35/src/tools).
The benchmark pattern and expected count come from
[rebar's definition](https://github.com/BurntSushi/rebar/blob/463d00f31887e84c38467805b9e3122c314b9521/benchmarks/definitions/curated/10-bounded-repeat.toml).
Rebar is published under the [Unlicense](https://github.com/BurntSushi/rebar/blob/463d00f31887e84c38467805b9e3122c314b9521/UNLICENSE).

The underlying Rust source is offered under MIT or Apache-2.0, at the user's
option, under the [Rust copyright terms](https://github.com/rust-lang/rust/blob/3b0d4813ab461ec81eab8980bb884691c97c5a35/COPYRIGHT).
We redistribute this benchmark input under the MIT option. The required notice
follows; SafeRE's own BSD-3-Clause license is unchanged.

Copyright (c) The Rust Project Contributors.

Permission is hereby granted, free of charge, to any
person obtaining a copy of this software and associated
documentation files (the "Software"), to deal in the
Software without restriction, including without
limitation the rights to use, copy, modify, merge,
publish, distribute, sublicense, and/or sell copies of
the Software, and to permit persons to whom the Software
is furnished to do so, subject to the following
conditions:

The above copyright notice and this permission notice
shall be included in all copies or substantial portions
of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF
ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED
TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A
PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT
SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY
CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION
OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR
IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER
DEALINGS IN THE SOFTWARE.
