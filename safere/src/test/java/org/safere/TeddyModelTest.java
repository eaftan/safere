// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

@DisabledForCrosscheck("implementation test uses package-private Teddy and Vector provider APIs")
class TeddyModelTest {

  @Test
  void compilationFollowsVectorProviderAvailability() {
    TeddyModel model = TeddyModel.compileForSelectedProvider(new String[] {"INFO", "WARN"});

    if (!VectorScanProviders.vectorProviderAvailable()) {
      assertThat(model).isNull();
    } else {
      assertThat(model).isNotNull();
      assertThat(VectorScanProviders.providerFor(ScanKind.CLASS, 64)).isNull();
      assertThat(VectorScanProviders.providerFor(ScanKind.TEDDY, 64)).isNull();
      assertThat(VectorScanProviders.providerFor(ScanKind.TEDDY, 256)).isNull();
      assertThat(VectorScanProviders.providerFor(ScanKind.CLASS, 1024)).isNotNull();
      assertThat(VectorScanProviders.providerFor(ScanKind.TEDDY, 1024)).isNotNull();
    }
  }
}
