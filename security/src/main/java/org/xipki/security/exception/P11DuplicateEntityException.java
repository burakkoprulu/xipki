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

package org.xipki.security.exception;

import org.xipki.security.pkcs11.P11EntityIdentifier;
import org.xipki.security.pkcs11.P11ObjectIdentifier;
import org.xipki.security.pkcs11.P11SlotIdentifier;

/**
 * TODO.
 * @author Lijun Liao
 * @since 2.0.0
 */

public class P11DuplicateEntityException extends P11TokenException {

  private static final long serialVersionUID = 1L;

  public P11DuplicateEntityException(P11EntityIdentifier entityId) {
    super("duplicate entity '" + entityId + "'");
  }

  public P11DuplicateEntityException(P11SlotIdentifier slotId, P11ObjectIdentifier objectId) {
    super("duplicate entity 'slot " + slotId + ", object " + objectId + "'");
  }

  public P11DuplicateEntityException(String message) {
    super(message);
  }

}
