package cn.edu.nju.cs;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

public final class MethodSignature {
    private final String name;
    private final List<Type> paramTypes;

    public MethodSignature(String name, List<Type> paramTypes) {
        this.name = name;
        this.paramTypes = List.copyOf(paramTypes);
    }

    public String name() {
        return name;
    }

    public List<Type> paramTypes() {
        return paramTypes;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof MethodSignature that)) {
            return false;
        }
        return Objects.equals(name, that.name) && Objects.equals(paramTypes, that.paramTypes);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, paramTypes);
    }

    @Override
    public String toString() {
        return name + "(" + paramTypes.stream().map(Type::keyword).collect(Collectors.joining(", ")) + ")";
    }
}
