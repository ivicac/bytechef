/*
 * Copyright 2021 <your company/name>.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.integri.atlas.task.auth.key.filesystem;

import com.integri.atlas.encryption.AbstractEncryptionKey;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * @author Ivica Cardic
 */
public class FileSystemEncryptionKey extends AbstractEncryptionKey {

    public FileSystemEncryptionKey() {
        try {
            Path keyPath = Files
                .createDirectories(Path.of(System.getProperty("user.home")).resolve(".integri"))
                .resolve("key");

            if (Files.exists(keyPath)) {
                key = Files.readString(keyPath);
            } else {
                Files.writeString(Files.createFile(keyPath), generateKey());
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
