/*
 * Copyright 2025 ByteChef
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

package com.bytechef.platform.data.table.configuration.domain;

import com.bytechef.platform.configuration.domain.Environment;
import com.bytechef.platform.constant.OwnerType;
import com.bytechef.platform.owner.Owner;
import java.time.Instant;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedBy;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.annotation.Version;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

/**
 * A webhook registration, which belongs to one data table and to the account whose run registered it.
 *
 * <p>
 * It once carried no owner, on the premise that the table it hangs off has exactly one so an owner here could only
 * agree with the table's or contradict it. Row-level ownership made that premise false: a shared table has no owner and
 * holds rows belonging to many accounts, so the table's identity no longer says whose row an event is about. Without an
 * owner here every registration on a shared table fired for every row written into it, and one account's row values
 * were POSTed to another's registered URL.
 *
 * <p>
 * The owner is {@code owner_id}/{@code owner_type} and the two are one value. They are read back as a pair by
 * {@link #getOwner()} rather than individually, because a half-written pair matches no predicate and belongs to nobody
 * -- and reading it as "no owner" would silently turn an account's registration into the vendor's.
 *
 * @author Ivica Cardic
 */
@Table("data_table_webhook")
public class DataTableWebhook {

    @Id
    private Long id;

    private String url;

    @Column
    private int type;

    @Column("data_table_id")
    private Long dataTableId;

    @Column("owner_id")
    private @Nullable Long ownerId;

    @Column("owner_type")
    private @Nullable Integer ownerType;

    @Column
    private int environment;

    @CreatedDate
    @Column("created_date")
    private Instant createdDate;

    @CreatedBy
    @Column("created_by")
    private String createdBy;

    @LastModifiedDate
    @Column("last_modified_date")
    private Instant lastModifiedDate;

    @LastModifiedBy
    @Column("last_modified_by")
    private String lastModifiedBy;

    @Version
    private Long version;

    public DataTableWebhook() {
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof DataTableWebhook dataTableWebhook)) {
            return false;
        }

        return Objects.equals(id, dataTableWebhook.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public DataTableWebhookType getType() {
        return DataTableWebhookType.values()[type];
    }

    public void setType(DataTableWebhookType type) {
        this.type = type.ordinal();
    }

    public Long getDataTableId() {
        return dataTableId;
    }

    public void setDataTableId(Long dataTableId) {
        this.dataTableId = dataTableId;
    }

    /**
     * The account whose run registered this webhook, or null for the vendor's own registration.
     *
     * <p>
     * The pair is rejected rather than interpreted when only one half is set. {@link #setOwner} writes both or neither,
     * so a half-written pair can only reach here through persistence -- a partial migration, or a hand-edited row --
     * and that is exactly when returning null would be worst: the registration would read as the vendor's, stop
     * receiving its own account's rows, and start receiving everybody's unowned ones.
     */
    public @Nullable Owner getOwner() {
        if (ownerId == null && ownerType == null) {
            return null;
        }

        if (ownerId == null || ownerType == null) {
            throw new IllegalStateException(
                "Data table webhook " + id + " has an incomplete owner: ownerId=" + ownerId + ", ownerType=" +
                    ownerType);
        }

        return new Owner(OwnerType.values()[ownerType], ownerId);
    }

    /**
     * Both columns move together, always. An {@code owner_id} beside a null {@code owner_type} matches no predicate and
     * belongs to nobody, so there is deliberately no setter for either column on its own.
     */
    public void setOwner(@Nullable Owner owner) {
        if (owner == null) {
            this.ownerId = null;
            this.ownerType = null;
        } else {
            this.ownerId = owner.id();

            OwnerType type = owner.type();

            this.ownerType = type.ordinal();
        }
    }

    public Environment getEnvironment() {
        return Environment.values()[environment];
    }

    public void setEnvironment(Environment environment) {
        this.environment = environment.ordinal();
    }

    public Instant getCreatedDate() {
        return createdDate;
    }

    public void setCreatedDate(Instant createdDate) {
        this.createdDate = createdDate;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(String createdBy) {
        this.createdBy = createdBy;
    }

    public Instant getLastModifiedDate() {
        return lastModifiedDate;
    }

    public void setLastModifiedDate(Instant lastModifiedDate) {
        this.lastModifiedDate = lastModifiedDate;
    }

    public String getLastModifiedBy() {
        return lastModifiedBy;
    }

    public void setLastModifiedBy(String lastModifiedBy) {
        this.lastModifiedBy = lastModifiedBy;
    }

    public Long getVersion() {
        return version;
    }

    public void setVersion(Long version) {
        this.version = version;
    }

    @Override
    public String toString() {
        return "DataTableWebhook{" +
            "id=" + id +
            ", url='" + url + '\'' +
            ", type=" + type +
            ", dataTableId=" + dataTableId +
            ", ownerId=" + ownerId +
            ", ownerType=" + ownerType +
            ", environment=" + environment +
            ", createdDate=" + createdDate +
            ", createdBy='" + createdBy + '\'' +
            ", lastModifiedDate=" + lastModifiedDate +
            ", lastModifiedBy='" + lastModifiedBy + '\'' +
            ", version=" + version +
            '}';
    }
}
