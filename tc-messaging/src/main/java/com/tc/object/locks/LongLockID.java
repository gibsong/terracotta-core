/*
 *
 *  The contents of this file are subject to the Terracotta Public License Version
 *  2.0 (the "License"); You may not use this file except in compliance with the
 *  License. You may obtain a copy of the License at
 *
 *  http://terracotta.org/legal/terracotta-public-license.
 *
 *  Software distributed under the License is distributed on an "AS IS" basis,
 *  WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License for
 *  the specific language governing rights and limitations under the License.
 *
 *  The Covered Software is Terracotta Core.
 *
 *  The Initial Developer of the Covered Software is
 *  Terracotta, Inc., a Software AG company
 *
 */
package com.tc.object.locks;

import com.tc.io.TCByteBufferInput;
import com.tc.io.TCByteBufferOutput;

import java.io.IOException;

public class LongLockID implements LockID {
  private static final long serialVersionUID = 0x2845dcae50983bcdL;

  private long              id;

  public LongLockID() {
    // to make TCSerializable happy
    this(-1);
  }

  /**
   * New id
   * 
   * @param id ID value
   */
  public LongLockID(long id) {
    this.id = id;
  }

  @Override
  public String toString() {
    return getClass().getSimpleName() + "(" + id + ")";
  }

  @Override
  public int hashCode() {
    return ((int) id) ^ ((int) (id >>> 32));
  }

  @Override
  public boolean equals(Object obj) {
    if (obj instanceof LongLockID) {
      LongLockID lid = (LongLockID) obj;
      return this.id == lid.id;
    }
    return false;
  }

  @Override
  public int compareTo(LockID o) {
    if (o instanceof LongLockID) {
      LongLockID other = (LongLockID) o;
      if (this.id < other.id) {
        return -1;
      } else if (this.id > other.id) {
        return 1;
      } else {
        return 0;
      }
    }

    return toString().compareTo(o.toString());
  }

  @Override
  public LongLockID deserializeFrom(TCByteBufferInput serialInput) throws IOException {
    this.id = serialInput.readLong();
    return this;
  }

  @Override
  public void serializeTo(TCByteBufferOutput serialOutput) {
    serialOutput.writeLong(id);
  }

  @Override
  public LockIDType getLockType() {
    return LockIDType.LONG;
  }
}
