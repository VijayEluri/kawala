/**
 * Copyright 2009 KaChing Group Inc. Licensed under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0 Unless required by applicable law
 * or agreed to in writing, software distributed under the License is
 * distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language
 * governing permissions and limitations under the License.
 */
package com.kaching.platform.converters;

import java.lang.reflect.Type;
import java.util.Map;

import com.google.inject.TypeLiteral;

public class Instantiators {

  /* The Instantiators class is the entry point into the library and is not
   * meant to be instantiated.
   */
  private Instantiators() {}

  /**
   * Creates an instantiator for {@code klass}.
   */
  @SuppressWarnings("unchecked")
  public static <T> Instantiator<T> createInstantiator(
      Class<T> klass, Converters... converters) {
    return factoryFor(klass, converters).build();
  }

  /**
   * Creates a converter {@code klass}.
   */
  @SuppressWarnings("unchecked")
  public static <T> Converter<T> createConverter(
      Class<T> klass, Converters... converters) {
    return createConverterForType(klass, converters);
  }

  /**
   * Creates a converter {@code typeLiteral}.
   */
  @SuppressWarnings("unchecked")
  public static <T> Converter<T> createConverter(
      TypeLiteral<T> typeLiteral, Converters... converters) {
    return createConverterForType(typeLiteral.getType(), converters);
  }

  @SuppressWarnings("unchecked")
  private static <T> InstantiatorImplFactory<T> factoryFor(Class<T> klass,
      Converters... converters) {
    InstantiatorImplFactory<T> factory = InstantiatorImplFactory
            .createFactory(klass);
    for (Converters c : converters) {
      c.addMeInto(factory);
    }
    return factory;
  }

  @SuppressWarnings("unchecked")
  private static <T> Converter<T> createConverterForType(Type type,
      Converters... converters) {
    return (Converter<T>) factoryFor(null, converters).createConverter(type).getOrThrow();
  }

  /**
   * Creates a {@link ConverterInstances} wrapper for the {@code instances} map.
   */
  @SuppressWarnings("unchecked")
  public static ConverterInstances instances(
      Map<? extends TypeLiteral<?>, ? extends Converter> instances) {
    return new ConverterInstances((Map) instances);
  }

  /**
   * Creates a {@link ConverterBindings} wrapper for the {@code bindings} map.
   */
  @SuppressWarnings("unchecked")
  public static ConverterBindings bindings(
      Map<? extends TypeLiteral<?>, ? extends Class<? extends Converter>> bindings) {
    return new ConverterBindings((Map) bindings);
  }

  public static abstract class Converters<V> {

    private final Map<? extends TypeLiteral<?>, ? extends V> map;

    protected Converters(Map<? extends TypeLiteral<?>, ? extends V> map) {
      this.map = map;
    }

    @SuppressWarnings("unchecked")
    Map<TypeLiteral<?>, V> get() {
      // Caller does not care about covariance of the key and value.
      return (Map<TypeLiteral<?>, V>) map;
    }

    abstract void addMeInto(InstantiatorImplFactory<?> factory);

  }

  /**
   * Wrapper to pass a {@code Map<TypeLiteral<?>, Converter<?>>}. We must use
   * wrappers since the API passes multiple maps whose erasure is the same
   * and thus illegal per the Java language spec.
   */
  public static class ConverterBindings extends Converters<Class<? extends Converter<?>>> {

    private ConverterBindings(
        Map<? extends TypeLiteral<?>, ? extends Class<? extends Converter<?>>> map) {
      super(map);
    }

    @Override
    void addMeInto(InstantiatorImplFactory<?> factory) {
      factory.addConverterBindings(get());
    }

  }

  /**
   * Wrapper to pass a {@code Map<TypeLiteral<?>, Converter<?>>}. We must use
   * wrappers since the API passes multiple maps whose erasure is the same
   * and thus illegal per the Java language spec.
   */
  public static class ConverterInstances extends Converters<Converter<?>> {

    private ConverterInstances(
        Map<? extends TypeLiteral<?>, ? extends Converter<?>> map) {
      super(map);
    }

    @Override
    void addMeInto(InstantiatorImplFactory<?> factory) {
      factory.addConverterInstances(get());
    }

  }

}
