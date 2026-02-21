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

public abstract class Closure<V> extends GroovyObjectSupport implements Cloneable, Runnable, Serializable {
    public static final int OWNER_FIRST = 0;
    public static final int DELEGATE_FIRST = 1;
    public static final int OWNER_ONLY = 2;
    public static final int DELEGATE_ONLY = 3;
    public static final int TO_SELF = 4;

    private Object delegate;
    private int resolveStrategy = OWNER_FIRST;

    public Closure(Object owner) {
    }

    public Closure(Object owner, Object thisObject) {
    }

    public void setDelegate(Object delegate) {
        this.delegate = delegate;
    }

    public Object getDelegate() {
        return delegate;
    }

    public void setResolveStrategy(int resolveStrategy) {
        this.resolveStrategy = resolveStrategy;
    }

    public int getResolveStrategy() {
        return resolveStrategy;
    }

    public V call() {
        return null;
    }

    public V call(Object... args) {
        return null;
    }

    public V call(Object arg) {
        return null;
    }

    @Override
    public void run() {
        call();
    }
}
