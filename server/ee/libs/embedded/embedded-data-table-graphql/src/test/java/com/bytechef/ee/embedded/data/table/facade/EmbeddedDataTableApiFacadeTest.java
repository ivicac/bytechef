/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.embedded.data.table.facade;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

import com.bytechef.platform.constant.PlatformType;
import com.bytechef.platform.data.table.configuration.service.DataTableService;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.prepost.PreAuthorize;

/**
 * @version ee
 *
 * @author Ivica Cardic
 */
@ExtendWith(MockitoExtension.class)
class EmbeddedDataTableApiFacadeTest {

    @Mock
    private DataTableService dataTableService;

    @InjectMocks
    private EmbeddedDataTableApiFacadeImpl embeddedDataTableApiFacade;

    /**
     * Not a tautology. The failure this catches is a later refactor moving the gate onto the controller, where it
     * compiles, reads as protected, and silently stops applying.
     *
     * <p>
     * Driven off the interface's own methods rather than a list written out here, so a method added to the facade is
     * covered the moment it exists.
     */
    @Test
    void testEveryFacadeMethodIsGatedOnTenantAdmin() throws Exception {
        Method[] methods = EmbeddedDataTableApiFacade.class.getDeclaredMethods();

        assertNotEquals(0, methods.length, "no facade methods found to check");

        for (Method method : methods) {
            assertTenantAdminGated(method.getName(), method.getParameterTypes());
        }
    }

    @Test
    void testGetDataTablesListsEveryTableInTheTenant() {
        embeddedDataTableApiFacade.getDataTables(0);

        verify(dataTableService).listTables(eq(0L), eq(PlatformType.EMBEDDED));
    }

    private static void assertTenantAdminGated(String methodName, Class<?>... parameterTypes) throws Exception {
        Method method = EmbeddedDataTableApiFacadeImpl.class.getMethod(methodName, parameterTypes);

        PreAuthorize preAuthorize = method.getAnnotation(PreAuthorize.class);

        assertNotNull(preAuthorize, methodName + " is not gated");
        assertEquals("isTenantAdmin()", preAuthorize.value());
    }
}
