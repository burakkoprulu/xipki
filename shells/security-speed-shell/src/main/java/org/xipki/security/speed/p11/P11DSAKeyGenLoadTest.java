/*
 *
 * Copyright (c) 2013 - 2018 Lijun Liao
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.xipki.security.speed.p11;

import org.xipki.security.pkcs11.P11ObjectIdentifier;
import org.xipki.security.pkcs11.P11Slot;

/**
 * TODO.
 * @author Lijun Liao
 * @since 2.0.0
 */
// CHECKSTYLE:SKIP
public class P11DSAKeyGenLoadTest extends P11KeyGenLoadTest {

  private final int plength;

  private final int qlength;

  public P11DSAKeyGenLoadTest(P11Slot slot, int plength, int qlength) throws Exception {
    super(slot, "PKCS#11 DSA key generation\nplength: " + plength + "\nqlength: " + qlength);
    this.plength = plength;
    this.qlength = qlength;
  }

  @Override
  protected void genKeypair() throws Exception {
    P11ObjectIdentifier objId = slot.generateDSAKeypair(plength, qlength, getDummyLabel(),
        getControl());
    slot.removeIdentity(objId);
  }

}
