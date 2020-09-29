/*
 * Copyright (C) 2020 Grakn Labs
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 *
 */

package grakn.core.concept.type.impl;

import grakn.core.common.exception.GraknException;
import grakn.core.common.iterator.Iterators;
import grakn.core.concept.type.AttributeType;
import grakn.core.concept.type.EntityType;
import grakn.core.concept.type.RelationType;
import grakn.core.concept.type.RoleType;
import grakn.core.concept.type.ThingType;
import grakn.core.common.iterator.ResourceIterator;
import grakn.core.graph.Graphs;
import grakn.core.graph.util.Encoding;
import grakn.core.graph.vertex.TypeVertex;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Stream;

import static grakn.common.util.Objects.className;
import static grakn.core.common.exception.ErrorMessage.ThingWrite.ILLEGAL_ABSTRACT_WRITE;
import static grakn.core.common.exception.ErrorMessage.Transaction.SESSION_SCHEMA_VIOLATION;
import static grakn.core.common.exception.ErrorMessage.TypeRead.INVALID_TYPE_CASTING;
import static grakn.core.common.exception.ErrorMessage.TypeWrite.SUPERTYPE_SELF;
import static grakn.core.common.iterator.Iterators.loop;
import static grakn.core.common.iterator.Iterators.tree;
import static grakn.core.graph.util.Encoding.Edge.Rule.CONCLUSION;
import static grakn.core.graph.util.Encoding.Edge.Rule.CONDITION_NEGATIVE;
import static grakn.core.graph.util.Encoding.Edge.Rule.CONDITION_POSITIVE;
import static grakn.core.graph.util.Encoding.Edge.Type.SUB;

public abstract class TypeImpl implements grakn.core.concept.type.Type {

    protected final Graphs graphs;
    public final TypeVertex vertex;

    TypeImpl(Graphs graphs, TypeVertex vertex) {
        this.graphs = graphs;
        this.vertex = Objects.requireNonNull(vertex);
    }

    TypeImpl(Graphs graphs, String label, Encoding.Vertex.Type encoding) {
        this(graphs, label, encoding, null);
    }

    TypeImpl(Graphs graphs, String label, Encoding.Vertex.Type encoding, String scope) {
        this.graphs = graphs;
        this.vertex = graphs.schema().create(encoding, label, scope);
        TypeVertex superTypeVertex = graphs.schema().getType(encoding.root().label(), encoding.root().scope());
        vertex.outs().put(SUB, superTypeVertex);
    }

    public static TypeImpl of(Graphs graphs, TypeVertex vertex) {
        switch (vertex.encoding()) {
            case ROLE_TYPE:
                return RoleTypeImpl.of(graphs, vertex);
            default:
                return ThingTypeImpl.of(graphs, vertex);
        }
    }

    @Override
    public boolean isDeleted() {
        return vertex.isDeleted();
    }

    @Override
    public boolean isRoot() {
        return false;
    }

    @Override
    public Long count() {
        return 0L; // TODO: return total number of type instances
    }

    @Override
    public void setLabel(String label) {
        vertex.label(label);
    }

    @Override
    public String getLabel() {
        return vertex.label();
    }

    @Override
    public boolean isAbstract() {
        return vertex.isAbstract();
    }

    @Override
    public Stream<RuleImpl> getPositiveConditionRules() {
        return vertex.ins().edge(CONDITION_POSITIVE).from().apply(v -> RuleImpl.of(graphs, v)).stream();
    }

    @Override
    public Stream<RuleImpl> getNegativeConditionRules() {
        return vertex.ins().edge(CONDITION_NEGATIVE).from().apply(v -> RuleImpl.of(graphs, v)).stream();
    }

    @Override
    public Stream<RuleImpl> getConcludingRules() {
        return vertex.ins().edge(CONCLUSION).from().apply(v -> RuleImpl.of(graphs, v)).stream();
    }

    void superTypeVertex(TypeVertex superTypeVertex) {
        if (vertex.equals(superTypeVertex)) throw exception(SUPERTYPE_SELF.message(vertex.label()));
        vertex.outs().edge(SUB, ((TypeImpl) getSupertype()).vertex).delete();
        vertex.outs().put(SUB, superTypeVertex);
    }

    @Nullable
    <TYPE extends grakn.core.concept.type.Type> TYPE getSupertype(final Function<TypeVertex, TYPE> typeConstructor) {
        ResourceIterator<TypeVertex> iterator = vertex.outs().edge(SUB).to().filter(v -> v.encoding().equals(vertex.encoding()));
        if (iterator.hasNext()) return typeConstructor.apply(iterator.next());
        else return null;
    }

    <TYPE extends grakn.core.concept.type.Type> Stream<TYPE> getSupertypes(final Function<TypeVertex, TYPE> typeConstructor) {
        return loop(
                vertex,
                v -> v != null && v.encoding().equals(this.vertex.encoding()),
                v -> {
                    ResourceIterator<TypeVertex> p = v.outs().edge(SUB).to();
                    if (p.hasNext()) return p.next();
                    else return null;
                }
        ).apply(typeConstructor).stream();
    }

    <TYPE extends grakn.core.concept.type.Type> Stream<TYPE> getSubtypes(final Function<TypeVertex, TYPE> typeConstructor) {
        return tree(vertex, v -> v.ins().edge(SUB).from()).apply(typeConstructor).stream();
    }

    @Override
    public List<GraknException> validate() {
        return new ArrayList<>();
    }

    @Override
    public TypeImpl asType() { return this; }

    @Override
    public ThingTypeImpl asThingType() {
        throw exception(INVALID_TYPE_CASTING.message(className(this.getClass()), className(ThingType.class)));
    }

    @Override
    public EntityTypeImpl asEntityType() {
        throw exception(INVALID_TYPE_CASTING.message(className(this.getClass()), className(EntityType.class)));
    }

    @Override
    public AttributeTypeImpl asAttributeType() {
        throw exception(INVALID_TYPE_CASTING.message(className(this.getClass()), className(AttributeType.class)));
    }

    @Override
    public RelationTypeImpl asRelationType() {
        throw exception(INVALID_TYPE_CASTING.message(className(this.getClass()), className(RelationType.class)));
    }

    @Override
    public RoleTypeImpl asRoleType() {
        throw exception(INVALID_TYPE_CASTING.message(className(this.getClass()), className(RoleType.class)));
    }

    void validateIsCommittedAndNotAbstract(Class<?> instanceClass) {
        if (vertex.status().equals(Encoding.Status.BUFFERED)) {
            throw exception(SESSION_SCHEMA_VIOLATION.message());
        } else if (isAbstract()) {
            throw exception(ILLEGAL_ABSTRACT_WRITE.message(instanceClass.getSimpleName(), getLabel()));
        }
    }

    @Override
    public GraknException exception(String message) {
        return graphs.exception(message);
    }

    @Override
    public String toString() {
        return className(this.getClass()) + " {" + vertex.toString() + "}";
    }

    @Override
    public boolean equals(Object object) {
        if (this == object) return true;
        if (object == null || getClass() != object.getClass()) return false;
        TypeImpl that = (TypeImpl) object;
        return this.vertex.equals(that.vertex);
    }

    @Override
    public final int hashCode() {
        return vertex.hashCode(); // does not need caching
    }
}
