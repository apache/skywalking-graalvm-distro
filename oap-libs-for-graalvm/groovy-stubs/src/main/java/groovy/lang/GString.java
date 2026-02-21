/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package groovy.lang;

import java.io.Serializable;

public abstract class GString extends GroovyObjectSupport
        implements CharSequence, Comparable<Object>, Serializable {

    public abstract String[] getStrings();

    public abstract Object[] getValues();

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        String[] strings = getStrings();
        Object[] values = getValues();
        for (int i = 0; i < strings.length; i++) {
            sb.append(strings[i]);
            if (i < values.length) {
                sb.append(values[i]);
            }
        }
        return sb.toString();
    }

    @Override
    public int length() {
        return toString().length();
    }

    @Override
    public char charAt(int index) {
        return toString().charAt(index);
    }

    @Override
    public CharSequence subSequence(int start, int end) {
        return toString().subSequence(start, end);
    }

    @Override
    public int compareTo(Object other) {
        return toString().compareTo(other.toString());
    }

    @Override
    public int hashCode() {
        return toString().hashCode();
    }

    @Override
    public boolean equals(Object obj) {
        if (obj instanceof GString) {
            return toString().equals(obj.toString());
        }
        if (obj instanceof String) {
            return toString().equals(obj);
        }
        return false;
    }
}
