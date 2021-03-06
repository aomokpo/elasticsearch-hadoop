/*
 * Copyright 2013 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.elasticsearch.hadoop.rest;

import org.elasticsearch.hadoop.util.BytesArray;

public class SimpleRequest implements Request {

    private final Method method;
    private final CharSequence uri;
    private final CharSequence path;
    private final CharSequence params;
    private final BytesArray body;

    public SimpleRequest(Method method, CharSequence uri, CharSequence path) {
        this(method, uri, path, null, null);
    }

    public SimpleRequest(Method method, CharSequence uri, CharSequence path, CharSequence params) {
        this(method, uri, path, params, null);
    }

    public SimpleRequest(Method method, CharSequence uri, CharSequence path, BytesArray body) {
        this(method, uri, path, null, body);
    }

    public SimpleRequest(Method method, CharSequence uri, CharSequence path, CharSequence params, BytesArray body) {
        this.method = method;
        this.uri = uri;
        this.path = path;
        this.params = params;
        this.body = body;
    }

    @Override
    public Method method() {
        return method;
    }

    @Override
    public CharSequence uri() {
        return uri;
    }

    @Override
    public CharSequence path() {
        return path;
    }

    @Override
    public CharSequence params() {
        return params;
    }

    @Override
    public BytesArray body() {
        return body;
    }
}