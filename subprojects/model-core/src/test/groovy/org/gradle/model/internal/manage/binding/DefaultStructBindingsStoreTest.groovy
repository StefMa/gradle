/*
 * Copyright 2016 the original author or authors.
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

package org.gradle.model.internal.manage.binding

import org.gradle.api.Named
import org.gradle.model.*
import org.gradle.model.internal.manage.schema.extract.DefaultModelSchemaExtractor
import org.gradle.model.internal.manage.schema.extract.DefaultModelSchemaStore
import org.gradle.model.internal.type.ModelType
import spock.lang.Specification
import spock.lang.Unroll

class DefaultStructBindingsStoreTest extends Specification {
    def schemaStore = new DefaultModelSchemaStore(DefaultModelSchemaExtractor.withDefaultStrategies())
    def bindingStore = new DefaultStructBindingsStore(schemaStore)

    def "extracts empty"() {
        def bindings = extract(Object)
        expect:
        bindings.declaredViewSchemas*.type*.rawClass as List == [Object]
        bindings.delegateSchema == null
        bindings.managedProperties.isEmpty()
        bindings.methodBindings.isEmpty()
    }

    static abstract class TypeWithAbstractProperty {
        abstract int getZ()
        abstract void setZ(int value)
    }

    def "extracts simple type with a managed property"() {
        def bindings = extract(TypeWithAbstractProperty)
        expect:
        bindings.declaredViewSchemas*.type*.rawClass as List == [TypeWithAbstractProperty]
        bindings.delegateSchema == null
        bindings.managedProperties.values()*.name as List == ["z"]
        bindings.methodBindings*.getClass() == [ManagedPropertyMethodBinding, ManagedPropertyMethodBinding]
        bindings.methodBindings*.source*.name == ["getZ", "setZ"]
    }

    static abstract class TypeWithImplementedProperty {
        int getZ() { 0 }
        void setZ(int value) {}
    }

    def "extracts simple type with an implemented property"() {
        def bindings = extract(TypeWithImplementedProperty)
        expect:
        bindings.declaredViewSchemas*.type*.rawClass as List == [TypeWithImplementedProperty]
        bindings.delegateSchema == null
        bindings.managedProperties.isEmpty()
        bindings.methodBindings*.source*.name == ["getZ", "setZ"]
        bindings.methodBindings*.getClass() == [DirectMethodBinding, DirectMethodBinding]
    }

    static class DelegateTypeWithImplementedProperty {
        int z
    }

    def "extracts simple type with a delegated property"() {
        def bindings = extract(TypeWithAbstractProperty, DelegateTypeWithImplementedProperty)
        expect:
        bindings.declaredViewSchemas*.type*.rawClass as List == [TypeWithAbstractProperty]
        bindings.delegateSchema.type.rawClass == DelegateTypeWithImplementedProperty
        bindings.managedProperties.isEmpty()
        bindings.methodBindings*.source*.name == ["getZ", "setZ"]
        bindings.methodBindings*.getClass() == [DelegateMethodBinding, DelegateMethodBinding]
    }

    def "fails when delegate type is abstract"() {
        when: extract(Object, Serializable)
        then: def ex = thrown InvalidManagedTypeException
        ex.message == "Type 'Object' is not a valid managed type: delegate type must be null or a non-abstract type instead of 'Serializable'."
    }

    @Managed
    static class EmptyStaticClass {}

    def "public type must be abstract"() {
        when: extract EmptyStaticClass
        then: def ex = thrown InvalidManagedTypeException
        ex.message == """Type $EmptyStaticClass.name is not a valid managed type:
- Must be defined as an interface or an abstract class."""
    }

    @Managed
    static interface ParameterizedEmptyInterface<T> {}

    def "public type cannot be parameterized"() {
        when: extract ParameterizedEmptyInterface
        then: def ex = thrown InvalidManagedTypeException
        ex.message == """Type $ParameterizedEmptyInterface.name is not a valid managed type:
- Cannot be a parameterized type."""
    }


    @Managed
    static abstract class WithInstanceScopedField {
        private String name
        private int age
    }

    def "instance scoped fields are not allowed"() {
        when:  extract WithInstanceScopedField
        then: def ex = thrown InvalidManagedTypeException
        ex.message == """Type $WithInstanceScopedField.name is not a valid managed type:
- Field name is not valid: Fields must be static final.
- Field age is not valid: Fields must be static final."""
    }

    @Managed
    static abstract class WithInstanceScopedFieldInSuperclass extends WithInstanceScopedField {
    }

    def "instance scoped fields are not allowed in super-class"() {
        when: extract WithInstanceScopedFieldInSuperclass
        then: def ex = thrown InvalidManagedTypeException
        ex.message == """Type $WithInstanceScopedFieldInSuperclass.name is not a valid managed type:
- Field WithInstanceScopedField.name is not valid: Fields must be static final.
- Field WithInstanceScopedField.age is not valid: Fields must be static final."""
    }

    @Managed
    static abstract class ProtectedAbstractMethods {
        protected abstract String getName()

        protected abstract void setName(String name)
    }

    @Managed
    static abstract class ProtectedAbstractMethodsInSuper extends ProtectedAbstractMethods {
    }

    def "protected abstract methods are not allowed"() {
        when:
        extract(ProtectedAbstractMethods)

        then:
        def e = thrown InvalidManagedTypeException
        e.message == """Type $ProtectedAbstractMethods.name is not a valid managed type:
- Method getName() is not a valid method: Protected and private methods are not supported.
- Method setName(java.lang.String) is not a valid method: Protected and private methods are not supported."""

        when:
        extract(ProtectedAbstractMethodsInSuper)

        then:
        e = thrown InvalidManagedTypeException
        e.message == """Type $ProtectedAbstractMethodsInSuper.name is not a valid managed type:
- Method ProtectedAbstractMethods.getName() is not a valid method: Protected and private methods are not supported.
- Method ProtectedAbstractMethods.setName(java.lang.String) is not a valid method: Protected and private methods are not supported."""
    }

    @Managed
    static abstract class ProtectedAndPrivateNonAbstractMethods {
        protected String getName() {
            return null;
        }

        private void setName(String name) {}
    }

    def "protected and private non-abstract methods are not allowed"() {
        when:
        extract ProtectedAndPrivateNonAbstractMethods
        then:
        def ex = thrown InvalidManagedTypeException
        ex.message == """Type $ProtectedAndPrivateNonAbstractMethods.name is not a valid managed type:
- Method setName(java.lang.String) is not a valid method: Protected and private methods are not supported.
- Method getName() is not a valid method: Protected and private methods are not supported."""
    }

    @Managed
    static abstract class ProtectedAndPrivateNonAbstractMethodsInSuper extends ProtectedAndPrivateNonAbstractMethods {
    }

    def "protected and private non-abstract methods are not allowed in super-type"() {
        when: extract ProtectedAndPrivateNonAbstractMethodsInSuper
        then: def ex = thrown InvalidManagedTypeException
        ex.message == """Type $ProtectedAndPrivateNonAbstractMethodsInSuper.name is not a valid managed type:
- Method ProtectedAndPrivateNonAbstractMethods.setName(java.lang.String) is not a valid method: Protected and private methods are not supported.
- Method ProtectedAndPrivateNonAbstractMethods.getName() is not a valid method: Protected and private methods are not supported."""
    }

    def "fails when implemented property is present in delegate"() {
        when:
        extract TypeWithImplementedProperty, DelegateTypeWithImplementedProperty
        then:
        def ex = thrown InvalidManagedTypeException
        ex.message == """Type $TypeWithImplementedProperty.name is not a valid managed type:
- Method getZ() is not a valid method: it is both implemented by the view '${getName(TypeWithImplementedProperty)}' and the delegate type '${getName(DelegateTypeWithImplementedProperty)}'
- Method setZ(int) is not a valid method: it is both implemented by the view '${getName(TypeWithImplementedProperty)}' and the delegate type '${getName(DelegateTypeWithImplementedProperty)}'"""
    }

    static abstract class TypeWithAbstractWriteOnlyProperty {
        abstract void setZ(int value)
    }

    def "fails when abstract property has only setter"() {
        when:
        extract(TypeWithAbstractWriteOnlyProperty)
        then:
        def ex = thrown InvalidManagedTypeException
        ex.message == """Type $TypeWithAbstractWriteOnlyProperty.name is not a valid managed type:
- Property 'z' is not valid: it must both have an abstract getter and a setter"""
    }

    static abstract class TypeWithInconsistentPropertyType {
        abstract String getZ()
        abstract void setZ(int value)
    }

    def "fails when property has inconsistent type"() {
        when:
        extract(TypeWithInconsistentPropertyType)
        then:
        def ex = thrown InvalidManagedTypeException
        ex.message == """Type $TypeWithInconsistentPropertyType.name is not a valid managed type:
- Method setZ(int) is not a valid method: it should take parameter with type 'String'"""
    }

    static interface OverloadingNumber {
        Number getValue()
    }

    static interface OverloadingInteger extends OverloadingNumber {
        @Override
        Integer getValue()
    }

    static class OverloadingNumberImpl implements OverloadingNumber {
        @Override
        Number getValue() { 1.0d }
    }

    static class OverloadingIntegerImpl extends OverloadingNumberImpl implements OverloadingInteger {
        @Override
        Integer getValue() { 2 }
    }

    def "detects overloads"() {
        def bindings = extract(OverloadingNumber, OverloadingIntegerImpl)
        expect:
        bindings.declaredViewSchemas*.type*.rawClass as List == [OverloadingNumber]
        bindings.delegateSchema.type.rawClass == OverloadingIntegerImpl
        bindings.managedProperties.isEmpty()
        bindings.methodBindings*.getClass() == [DelegateMethodBinding, DelegateMethodBinding]
        bindings.methodBindings*.source*.name == ["getValue", "getValue"]
        bindings.methodBindings*.source*.method*.returnType == [Number, Integer]
        bindings.methodBindings*.implementor*.name == ["getValue", "getValue"]
        bindings.methodBindings*.implementor*.method*.returnType == [Integer, Integer]
    }

    static enum MyEnum {
        A, B, C
    }

    @Managed
    static interface HasUnmanagedOnManaged {
        @Unmanaged
        MyEnum getMyEnum();
        void setMyEnum(MyEnum myEnum)
    }

    def "cannot annotate managed type property with unmanaged"() {
        when: extract HasUnmanagedOnManaged
        then: def ex = thrown InvalidManagedTypeException
        ex.message == """Type $HasUnmanagedOnManaged.name is not a valid managed type:
- Property 'myEnum' is not valid: it is marked as @Unmanaged, but is of @Managed type '${getName(MyEnum)}'; please remove the @Managed annotation"""
    }

    @Managed
    static interface NoSetterForUnmanaged {
        @Unmanaged
        InputStream getThing();
    }

    def "must have setter for unmanaged"() {
        when: extract NoSetterForUnmanaged
        then: def ex = thrown InvalidManagedTypeException
        ex.message == """Type $NoSetterForUnmanaged.name is not a valid managed type:
- Property 'thing' is not valid: it must not be read only, because it is marked as @Unmanaged"""
    }

    @Managed
    static interface AddsSetterToNoSetterForUnmanaged extends NoSetterForUnmanaged {
        void setThing(InputStream inputStream);
    }

    def "subtype can add unmanaged setter"() {
        def bindings = extract(AddsSetterToNoSetterForUnmanaged)
        expect:
        bindings.getManagedProperty("thing").type == ModelType.of(InputStream)
    }

    @Managed
    static abstract class WritableMapProperty {
        abstract void setMap(ModelMap<NamedThingInterface> map)
        abstract ModelMap<NamedThingInterface> getMap()
    }

    @Managed
    static abstract class WritableSetProperty {
        abstract void setSet(ModelSet<NamedThingInterface> set)
        abstract ModelSet<NamedThingInterface> getSet()
    }

    def "map cannot be writable"() {
        when: extract WritableMapProperty
        then: def ex = thrown InvalidManagedTypeException
        ex.message == """Type $WritableMapProperty.name is not a valid managed type:
- Property 'map' is not valid: it cannot have a setter (ModelMap properties must be read only)"""
    }

    def "set cannot be writable"() {
        when: extract WritableSetProperty
        then: def ex = thrown InvalidManagedTypeException
        ex.message == """Type $WritableSetProperty.name is not a valid managed type:
- Property 'set' is not valid: it cannot have a setter (ModelSet properties must be read only)"""
    }

    @Managed
    static interface GetterWithParams {
        String getName(String name)
        void setName(String name)
    }

    def "malformed getter"() {
        when: extract GetterWithParams
        then: def ex = thrown InvalidManagedTypeException
        ex.message == """Type $GetterWithParams.name is not a valid managed type:
- Method getName(java.lang.String) is not a valid property accessor method: getter method must not take parameters
- Property 'name' is not valid: it must both have an abstract getter and a setter"""
    }

    @Managed
    static interface NonVoidSetter {
        String getName()
        String setName(String name)
    }

    def "non void setter"() {
        when: extract NonVoidSetter
        then: def ex = thrown InvalidManagedTypeException
        ex.message == """Type $NonVoidSetter.name is not a valid managed type:
- Method setName(java.lang.String) is not a valid property accessor method: setter method must have void return type"""
    }

    @Managed
    static interface SetterWithExtraParams {
        String getName()
        void setName(String name, String otherName)
    }

    def "setter with extra params"() {
        when: extract SetterWithExtraParams
        then: def ex = thrown InvalidManagedTypeException
        ex.message == """Type $SetterWithExtraParams.name is not a valid managed type:
- Method setName(java.lang.String, java.lang.String) is not a valid property accessor method: setter method must take exactly one parameter"""
    }

    @Managed
    static interface HasExtraNonPropertyMethods {
        String getName()

        void setName(String name)

        void foo(String bar)
    }

    @Managed
    static interface ChildWithExtraNonPropertyMethods extends HasExtraNonPropertyMethods {
    }

    def "can only have abstract getters and setters"() {
        when: extract HasExtraNonPropertyMethods
        then: def ex = thrown InvalidManagedTypeException
        ex.message == """Type $HasExtraNonPropertyMethods.name is not a valid managed type:
- Method foo(java.lang.String) is not a valid managed type method: it must have an implementation"""
    }

    def "can only have abstract getters and setters in inherited type"() {
        when: extract ChildWithExtraNonPropertyMethods
        then: def ex = thrown InvalidManagedTypeException
        ex.message == """Type $ChildWithExtraNonPropertyMethods.name is not a valid managed type:
- Method ${HasExtraNonPropertyMethods.simpleName}.foo(java.lang.String) is not a valid managed type method: it must have an implementation"""
    }

    @Managed
    interface HasTwoFirstsCharLowercaseGetter {
        String getccCompiler()
    }

    def "reject two firsts char lowercase getters"() {
        when: extract HasTwoFirstsCharLowercaseGetter
        then: def ex = thrown InvalidManagedTypeException
        ex.message == """Type $HasTwoFirstsCharLowercaseGetter.name is not a valid managed type:
- Method getccCompiler() is not a valid managed type method: it must have an implementation"""
    }

    @Managed
    interface HasGetGetterLikeMethod {
        String gettingStarted()
    }

    def "get-getters-like methods not considered as getters"() {
        when: extract HasGetGetterLikeMethod
        then: def ex = thrown InvalidManagedTypeException
        ex.message == """Type $HasGetGetterLikeMethod.name is not a valid managed type:
- Method gettingStarted() is not a valid managed type method: it must have an implementation"""
    }

    @Managed
    interface HasIsGetterLikeMethod {
        boolean isidore()
    }

    def "is-getters-like methods not considered as getters"() {
        when: extract HasIsGetterLikeMethod
        then: def ex = thrown InvalidManagedTypeException
        ex.message == """Type $HasIsGetterLikeMethod.name is not a valid managed type:
- Method isidore() is not a valid managed type method: it must have an implementation"""
    }

    @Managed
    interface HasSetterLikeMethod {
        void settings(String settings)
    }

    def "setters-like methods not considered as setters"() {
        when: extract HasSetterLikeMethod
        then: def ex = thrown InvalidManagedTypeException
        ex.message == """Type $HasSetterLikeMethod.name is not a valid managed type:
- Method settings(java.lang.String) is not a valid managed type method: it must have an implementation"""
    }

    @Managed
    static interface MisalignedSetterType {
        String getThing()
        void setThing(Object name)
    }

    def "misaligned setter type"() {
        when: def bindings = extract MisalignedSetterType
        then: def ex = thrown InvalidManagedTypeException
        ex.message == """Type $MisalignedSetterType.name is not a valid managed type:
- Method setThing(java.lang.Object) is not a valid method: it should take parameter with type 'String'"""
    }

    @Managed
    static abstract class NonAbstractGetterWithSetter {
        String getName() {}
        abstract void setName(String name)
    }

    @Managed
    static abstract class NonAbstractSetter {
        abstract String getName()
        void setName(String name) {}
    }

    def "non-abstract getter with abstract setter is not allowed"() {
        when: extract NonAbstractGetterWithSetter
        then: def ex = thrown InvalidManagedTypeException
        ex.message == """Type $NonAbstractGetterWithSetter.name is not a valid managed type:
- Property 'name' is not valid: it must have either only abstract accessor methods or only implemented accessor methods"""
    }

    def "non-abstract setter without getter is not allowed"() {
        when: extract NonAbstractSetter
        then: def ex = thrown InvalidManagedTypeException
        ex.message == """Type $NonAbstractSetter.name is not a valid managed type:
- Property 'name' is not valid: it must have either only abstract accessor methods or only implemented accessor methods"""
    }

    @Managed
    static interface CollectionType {
        List<String> getItems()
        void setItems(List<Integer> integers)
    }

    def "displays a reasonable error message when getter and setter of a property of collection of scalar types do not use the same generic type"() {
        given: when: extract CollectionType
        then: def ex = thrown InvalidManagedTypeException
        ex.message == """Type $CollectionType.name is not a valid managed type:
- Method setItems(java.util.List<java.lang.Integer>) is not a valid method: it should take parameter with type 'List<String>'"""
    }

    @Unroll
    def "misaligned types #firstType.simpleName and #secondType.simpleName"() {
        def interfaceWithPrimitiveProperty = new GroovyClassLoader(getClass().classLoader).parseClass """
            import org.gradle.model.Managed

            @Managed
            interface PrimitiveProperty {
                $firstType.name getPrimitiveProperty()

                void setPrimitiveProperty($secondType.name value)
            }
        """
        when: extract interfaceWithPrimitiveProperty
        then: def ex = thrown InvalidManagedTypeException
        ex.message == """Type PrimitiveProperty is not a valid managed type:
- Method setPrimitiveProperty($secondType.name) is not a valid method: it should take parameter with type '$firstType.simpleName'"""

        where:
        firstType | secondType
        byte      | Byte
        boolean   | Boolean
        char      | Character
        float     | Float
        long      | Long
        short     | Short
        int       | Integer
        double    | Double
        Byte      | byte
        Boolean   | boolean
        Character | char
        Float     | float
        Long      | long
        Short     | short
        Integer   | int
        Double    | double
    }

    @Managed
    abstract static class MutableName implements Named {
        abstract void setName(String name)
    }

    def "Named cannot have setName"() {
        when:
        extract MutableName

        then:
        def e = thrown Exception
        e.message == """Type $MutableName.name is not a valid managed type:
- Property 'name' is not valid: it must not have a setter, because the type implements '$Named.name'"""
    }

    @Managed
    static interface HasIsAndGetPropertyWithDifferentTypes {
        boolean isValue()
        String getValue()
    }

    def "handles is/get property with non-matching type"() {
        when: extract HasIsAndGetPropertyWithDifferentTypes
        then: def ex = thrown InvalidManagedTypeException
        ex.message == """Type $HasIsAndGetPropertyWithDifferentTypes.name is not a valid managed type:
- Property 'value' is not valid: it must have a consistent type, but it's defined as String, boolean"""
    }

    @Managed
    interface IsNotAllowedForOtherTypeThanBoolean {
        String isThing()
        void setThing(String thing)
    }

    @Managed
    interface IsNotAllowedForOtherTypeThanBooleanWithBoxedBoolean {
        Boolean isThing()
        void setThing(Boolean thing)
    }

    @Unroll
    def "should not allow 'is' as a prefix for getter on non primitive boolean in #managedType"() {
        when: extract type
        then: def ex = thrown InvalidManagedTypeException
        ex.message == """Type $type.name is not a valid managed type:
- Method isThing() is not a valid method: it should either return 'boolean', or its name should be 'getThing()'"""

        where:
        type << [IsNotAllowedForOtherTypeThanBoolean, IsNotAllowedForOtherTypeThanBooleanWithBoxedBoolean]
    }

    @Managed
    static abstract class ConstructorWithArguments {
        ConstructorWithArguments(String arg) {}
    }

    @Managed
    static abstract class AdditionalConstructorWithArguments {
        AdditionalConstructorWithArguments() {}
        AdditionalConstructorWithArguments(String arg) {}
    }

    static class SuperConstructorWithArguments {
        SuperConstructorWithArguments(String arg) {}
    }

    @Managed
    static abstract class ConstructorCallingSuperConstructorWithArgs extends SuperConstructorWithArguments {
        ConstructorCallingSuperConstructorWithArgs() {
            super("foo")
        }
    }

    @Managed
    static abstract class CustomConstructorInSuperClass extends ConstructorCallingSuperConstructorWithArgs {
    }

    def "custom constructors are not allowed"() {
        when: extract ConstructorWithArguments
        then: def ex = thrown InvalidManagedTypeException
        ex.message == """Type $ConstructorWithArguments.name is not a valid managed type:
- Constructor ConstructorWithArguments(java.lang.String) is not valid: Custom constructors are not supported."""

        when: extract AdditionalConstructorWithArguments
        then: ex = thrown InvalidManagedTypeException
        ex.message == """Type $AdditionalConstructorWithArguments.name is not a valid managed type:
- Constructor AdditionalConstructorWithArguments(java.lang.String) is not valid: Custom constructors are not supported."""

        when: extract CustomConstructorInSuperClass
        then: ex = thrown InvalidManagedTypeException
        ex.message == """Type $CustomConstructorInSuperClass.name is not a valid managed type:
- Constructor SuperConstructorWithArguments(java.lang.String) is not valid: Custom constructors are not supported."""
    }

    static abstract class MultipleProblemsSuper {
        private String field1
        MultipleProblemsSuper(String s) {}
        private String getPrivate() { field1 }
    }

    @Managed
    static class MultipleProblems<T extends List<?>> extends MultipleProblemsSuper {
        private String field2
        MultipleProblems(String s) { super(s) }
    }

    def "collects all problems for a type"() {
        when: extract MultipleProblems
        then: def ex = thrown InvalidManagedTypeException
        ex.message == """Type $MultipleProblems.name is not a valid managed type:
- Must be defined as an interface or an abstract class.
- Cannot be a parameterized type.
- Constructor MultipleProblems(java.lang.String) is not valid: Custom constructors are not supported.
- Field field2 is not valid: Fields must be static final.
- Constructor MultipleProblemsSuper(java.lang.String) is not valid: Custom constructors are not supported.
- Field MultipleProblemsSuper.field1 is not valid: Fields must be static final.
- Method MultipleProblemsSuper.getPrivate() is not a valid method: Protected and private methods are not supported."""
    }


    def extract(Class<?> type, Class<?> delegateType = null) {
        return extract(type, [], delegateType)
    }
    def extract(Class<?> type, List<Class<?>> viewTypes, Class<?> delegateType = null) {
        return bindingStore.getBindings(
            ModelType.of(type),
            viewTypes.collect { ModelType.of(it) },
            delegateType == null ? null : ModelType.of(delegateType)
        )
    }

    @Unroll
    def "finds #results.simpleName as the converging types among #types.simpleName"() {
        expect:
        DefaultStructBindingsStore.findConvergingTypes(types.collect { ModelType.of(it) }) as List == results.collect { ModelType.of(it) }

        where:
        types                                 | results
        [Object]                              | [Object]
        [Object, Serializable]                | [Serializable]
        [Object, Number, Comparable, Integer] | [Integer]
        [Integer, Object, Number, Comparable] | [Integer]
        [Integer, Double]                     | [Integer, Double]
        [Integer, Object, Double]             | [Integer, Double]
        [Integer, Object, Comparable, Double] | [Integer, Double]
    }

    String getName(ModelType<?> type) {
        type.displayName
    }

    String getName(Class<?> type) {
        getName(ModelType.of(type))
    }
}
