/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.embedded.configuration.web.rest.mapper;

import com.bytechef.ee.embedded.configuration.web.rest.model.ConnectionModel.CredentialStoreTypeEnum;
import com.bytechef.platform.connection.service.ConnectionCredentialStoreType;
import org.springframework.stereotype.Component;

/**
 * @version ee
 *
 * @author Ivica Cardic
 */
@Component("com.bytechef.ee.embedded.configuration.web.rest.mapper.CredentialStoreTypeMapper")
public class CredentialStoreTypeMapper {

    public CredentialStoreTypeEnum mapToCredentialStoreTypeEnum(ConnectionCredentialStoreType credentialStoreType) {
        if (credentialStoreType == null) {
            return null;
        }

        return CredentialStoreTypeEnum.valueOf(credentialStoreType.name());
    }

    public ConnectionCredentialStoreType mapToConnectionCredentialStoreType(
        CredentialStoreTypeEnum credentialStoreTypeEnum) {

        if (credentialStoreTypeEnum == null) {
            return null;
        }

        return ConnectionCredentialStoreType.valueOf(credentialStoreTypeEnum.name());
    }
}
