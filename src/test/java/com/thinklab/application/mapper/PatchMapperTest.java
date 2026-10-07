package com.thinklab.application.mapper;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;

import static org.junit.jupiter.api.Assertions.assertThrows;

class PatchMapperTest {

    @Test
    @DisplayName("the mapper is a utility class")
    void utility() throws ReflectiveOperationException {
        Constructor<PatchMapper> constructor = PatchMapper.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        assertThrows(InvocationTargetException.class, constructor::newInstance);
    }
}
