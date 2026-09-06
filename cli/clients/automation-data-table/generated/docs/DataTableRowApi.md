# DataTableRowApi

All URIs are relative to */api/automation/v1*

| Method | HTTP request | Description |
|------------- | ------------- | -------------|
| [**batchRows**](DataTableRowApi.md#batchRows) | **POST** /data-tables/{name}/rows/batch | Batch insert or upsert rows |
| [**batchRowsWithHttpInfo**](DataTableRowApi.md#batchRowsWithHttpInfo) | **POST** /data-tables/{name}/rows/batch | Batch insert or upsert rows |
| [**clearRows**](DataTableRowApi.md#clearRows) | **POST** /data-tables/{name}/rows/clear | Clear a table |
| [**clearRowsWithHttpInfo**](DataTableRowApi.md#clearRowsWithHttpInfo) | **POST** /data-tables/{name}/rows/clear | Clear a table |
| [**createRow**](DataTableRowApi.md#createRow) | **POST** /data-tables/{name}/rows | Insert a row |
| [**createRowWithHttpInfo**](DataTableRowApi.md#createRowWithHttpInfo) | **POST** /data-tables/{name}/rows | Insert a row |
| [**deleteRow**](DataTableRowApi.md#deleteRow) | **DELETE** /data-tables/{name}/rows/{id} | Delete a row |
| [**deleteRowWithHttpInfo**](DataTableRowApi.md#deleteRowWithHttpInfo) | **DELETE** /data-tables/{name}/rows/{id} | Delete a row |
| [**deleteRowByExternalId**](DataTableRowApi.md#deleteRowByExternalId) | **DELETE** /data-tables/{name}/rows/by-external-id/{externalId} | Delete a row by external id |
| [**deleteRowByExternalIdWithHttpInfo**](DataTableRowApi.md#deleteRowByExternalIdWithHttpInfo) | **DELETE** /data-tables/{name}/rows/by-external-id/{externalId} | Delete a row by external id |
| [**deleteRows**](DataTableRowApi.md#deleteRows) | **DELETE** /data-tables/{name}/rows | Delete rows |
| [**deleteRowsWithHttpInfo**](DataTableRowApi.md#deleteRowsWithHttpInfo) | **DELETE** /data-tables/{name}/rows | Delete rows |
| [**exportRows**](DataTableRowApi.md#exportRows) | **GET** /data-tables/{name}/rows/export | Export rows as CSV |
| [**exportRowsWithHttpInfo**](DataTableRowApi.md#exportRowsWithHttpInfo) | **GET** /data-tables/{name}/rows/export | Export rows as CSV |
| [**getRow**](DataTableRowApi.md#getRow) | **GET** /data-tables/{name}/rows/{id} | Get a row |
| [**getRowWithHttpInfo**](DataTableRowApi.md#getRowWithHttpInfo) | **GET** /data-tables/{name}/rows/{id} | Get a row |
| [**getRowByExternalId**](DataTableRowApi.md#getRowByExternalId) | **GET** /data-tables/{name}/rows/by-external-id/{externalId} | Get a row by external id |
| [**getRowByExternalIdWithHttpInfo**](DataTableRowApi.md#getRowByExternalIdWithHttpInfo) | **GET** /data-tables/{name}/rows/by-external-id/{externalId} | Get a row by external id |
| [**importRows**](DataTableRowApi.md#importRows) | **POST** /data-tables/{name}/rows/import | Import rows from CSV |
| [**importRowsWithHttpInfo**](DataTableRowApi.md#importRowsWithHttpInfo) | **POST** /data-tables/{name}/rows/import | Import rows from CSV |
| [**listRows**](DataTableRowApi.md#listRows) | **GET** /data-tables/{name}/rows | Query rows |
| [**listRowsWithHttpInfo**](DataTableRowApi.md#listRowsWithHttpInfo) | **GET** /data-tables/{name}/rows | Query rows |
| [**updateRow**](DataTableRowApi.md#updateRow) | **PATCH** /data-tables/{name}/rows/{id} | Update a row |
| [**updateRowWithHttpInfo**](DataTableRowApi.md#updateRowWithHttpInfo) | **PATCH** /data-tables/{name}/rows/{id} | Update a row |
| [**upsertRowByExternalId**](DataTableRowApi.md#upsertRowByExternalId) | **PUT** /data-tables/{name}/rows/by-external-id/{externalId} | Upsert a row by external id |
| [**upsertRowByExternalIdWithHttpInfo**](DataTableRowApi.md#upsertRowByExternalIdWithHttpInfo) | **PUT** /data-tables/{name}/rows/by-external-id/{externalId} | Upsert a row by external id |



## batchRows

> BatchRowsResponseModel batchRows(name, batchRowsRequestModel, xEnvironment)

Batch insert or upsert rows

Insert or upsert up to 1000 rows in one transaction; one failure rolls back all of them. Under UPSERT every row must carry an externalId.

### Example

```java
// Import classes:
import com.bytechef.cli.client.automationdatatable.ApiClient;
import com.bytechef.cli.client.automationdatatable.ApiException;
import com.bytechef.cli.client.automationdatatable.Configuration;
import com.bytechef.cli.client.automationdatatable.models.*;
import com.bytechef.cli.client.automationdatatable.api.DataTableRowApi;

public class Example {
    public static void main(String[] args) {
        ApiClient defaultClient = Configuration.getDefaultApiClient();
        defaultClient.setBasePath("/api/automation/v1");

        DataTableRowApi apiInstance = new DataTableRowApi(defaultClient);
        String name = "name_example"; // String | The table name.
        BatchRowsRequestModel batchRowsRequestModel = new BatchRowsRequestModel(); // BatchRowsRequestModel | 
        EnvironmentModel xEnvironment = EnvironmentModel.fromValue("DEVELOPMENT"); // EnvironmentModel | The environment whose physical table is addressed. PRODUCTION when omitted.
        try {
            BatchRowsResponseModel result = apiInstance.batchRows(name, batchRowsRequestModel, xEnvironment);
            System.out.println(result);
        } catch (ApiException e) {
            System.err.println("Exception when calling DataTableRowApi#batchRows");
            System.err.println("Status code: " + e.getCode());
            System.err.println("Reason: " + e.getResponseBody());
            System.err.println("Response headers: " + e.getResponseHeaders());
            e.printStackTrace();
        }
    }
}
```

### Parameters


| Name | Type | Description  | Notes |
|------------- | ------------- | ------------- | -------------|
| **name** | **String**| The table name. | |
| **batchRowsRequestModel** | [**BatchRowsRequestModel**](BatchRowsRequestModel.md)|  | |
| **xEnvironment** | [**EnvironmentModel**](.md)| The environment whose physical table is addressed. PRODUCTION when omitted. | [optional] [enum: DEVELOPMENT, STAGING, PRODUCTION] |

### Return type

[**BatchRowsResponseModel**](BatchRowsResponseModel.md)


### Authorization

No authorization required

### HTTP request headers

- **Content-Type**: application/json
- **Accept**: application/json, application/problem+json

### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
| **200** | The rows as stored. |  -  |
| **400** | The problem. |  -  |
| **404** | The problem. |  -  |
| **409** | The problem. |  -  |
| **507** | The problem. |  -  |

## batchRowsWithHttpInfo

> ApiResponse<BatchRowsResponseModel> batchRowsWithHttpInfo(name, batchRowsRequestModel, xEnvironment)

Batch insert or upsert rows

Insert or upsert up to 1000 rows in one transaction; one failure rolls back all of them. Under UPSERT every row must carry an externalId.

### Example

```java
// Import classes:
import com.bytechef.cli.client.automationdatatable.ApiClient;
import com.bytechef.cli.client.automationdatatable.ApiException;
import com.bytechef.cli.client.automationdatatable.ApiResponse;
import com.bytechef.cli.client.automationdatatable.Configuration;
import com.bytechef.cli.client.automationdatatable.models.*;
import com.bytechef.cli.client.automationdatatable.api.DataTableRowApi;

public class Example {
    public static void main(String[] args) {
        ApiClient defaultClient = Configuration.getDefaultApiClient();
        defaultClient.setBasePath("/api/automation/v1");

        DataTableRowApi apiInstance = new DataTableRowApi(defaultClient);
        String name = "name_example"; // String | The table name.
        BatchRowsRequestModel batchRowsRequestModel = new BatchRowsRequestModel(); // BatchRowsRequestModel | 
        EnvironmentModel xEnvironment = EnvironmentModel.fromValue("DEVELOPMENT"); // EnvironmentModel | The environment whose physical table is addressed. PRODUCTION when omitted.
        try {
            ApiResponse<BatchRowsResponseModel> response = apiInstance.batchRowsWithHttpInfo(name, batchRowsRequestModel, xEnvironment);
            System.out.println("Status code: " + response.getStatusCode());
            System.out.println("Response headers: " + response.getHeaders());
            System.out.println("Response body: " + response.getData());
        } catch (ApiException e) {
            System.err.println("Exception when calling DataTableRowApi#batchRows");
            System.err.println("Status code: " + e.getCode());
            System.err.println("Response headers: " + e.getResponseHeaders());
            System.err.println("Reason: " + e.getResponseBody());
            e.printStackTrace();
        }
    }
}
```

### Parameters


| Name | Type | Description  | Notes |
|------------- | ------------- | ------------- | -------------|
| **name** | **String**| The table name. | |
| **batchRowsRequestModel** | [**BatchRowsRequestModel**](BatchRowsRequestModel.md)|  | |
| **xEnvironment** | [**EnvironmentModel**](.md)| The environment whose physical table is addressed. PRODUCTION when omitted. | [optional] [enum: DEVELOPMENT, STAGING, PRODUCTION] |

### Return type

ApiResponse<[**BatchRowsResponseModel**](BatchRowsResponseModel.md)>


### Authorization

No authorization required

### HTTP request headers

- **Content-Type**: application/json
- **Accept**: application/json, application/problem+json

### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
| **200** | The rows as stored. |  -  |
| **400** | The problem. |  -  |
| **404** | The problem. |  -  |
| **409** | The problem. |  -  |
| **507** | The problem. |  -  |


## clearRows

> ClearRowsResponseModel clearRows(name, xEnvironment)

Clear a table

Delete every row. Irreversible.

### Example

```java
// Import classes:
import com.bytechef.cli.client.automationdatatable.ApiClient;
import com.bytechef.cli.client.automationdatatable.ApiException;
import com.bytechef.cli.client.automationdatatable.Configuration;
import com.bytechef.cli.client.automationdatatable.models.*;
import com.bytechef.cli.client.automationdatatable.api.DataTableRowApi;

public class Example {
    public static void main(String[] args) {
        ApiClient defaultClient = Configuration.getDefaultApiClient();
        defaultClient.setBasePath("/api/automation/v1");

        DataTableRowApi apiInstance = new DataTableRowApi(defaultClient);
        String name = "name_example"; // String | The table name.
        EnvironmentModel xEnvironment = EnvironmentModel.fromValue("DEVELOPMENT"); // EnvironmentModel | The environment whose physical table is addressed. PRODUCTION when omitted.
        try {
            ClearRowsResponseModel result = apiInstance.clearRows(name, xEnvironment);
            System.out.println(result);
        } catch (ApiException e) {
            System.err.println("Exception when calling DataTableRowApi#clearRows");
            System.err.println("Status code: " + e.getCode());
            System.err.println("Reason: " + e.getResponseBody());
            System.err.println("Response headers: " + e.getResponseHeaders());
            e.printStackTrace();
        }
    }
}
```

### Parameters


| Name | Type | Description  | Notes |
|------------- | ------------- | ------------- | -------------|
| **name** | **String**| The table name. | |
| **xEnvironment** | [**EnvironmentModel**](.md)| The environment whose physical table is addressed. PRODUCTION when omitted. | [optional] [enum: DEVELOPMENT, STAGING, PRODUCTION] |

### Return type

[**ClearRowsResponseModel**](ClearRowsResponseModel.md)


### Authorization

No authorization required

### HTTP request headers

- **Content-Type**: Not defined
- **Accept**: application/json, application/problem+json

### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
| **200** | How many rows were deleted. |  -  |
| **404** | The problem. |  -  |

## clearRowsWithHttpInfo

> ApiResponse<ClearRowsResponseModel> clearRowsWithHttpInfo(name, xEnvironment)

Clear a table

Delete every row. Irreversible.

### Example

```java
// Import classes:
import com.bytechef.cli.client.automationdatatable.ApiClient;
import com.bytechef.cli.client.automationdatatable.ApiException;
import com.bytechef.cli.client.automationdatatable.ApiResponse;
import com.bytechef.cli.client.automationdatatable.Configuration;
import com.bytechef.cli.client.automationdatatable.models.*;
import com.bytechef.cli.client.automationdatatable.api.DataTableRowApi;

public class Example {
    public static void main(String[] args) {
        ApiClient defaultClient = Configuration.getDefaultApiClient();
        defaultClient.setBasePath("/api/automation/v1");

        DataTableRowApi apiInstance = new DataTableRowApi(defaultClient);
        String name = "name_example"; // String | The table name.
        EnvironmentModel xEnvironment = EnvironmentModel.fromValue("DEVELOPMENT"); // EnvironmentModel | The environment whose physical table is addressed. PRODUCTION when omitted.
        try {
            ApiResponse<ClearRowsResponseModel> response = apiInstance.clearRowsWithHttpInfo(name, xEnvironment);
            System.out.println("Status code: " + response.getStatusCode());
            System.out.println("Response headers: " + response.getHeaders());
            System.out.println("Response body: " + response.getData());
        } catch (ApiException e) {
            System.err.println("Exception when calling DataTableRowApi#clearRows");
            System.err.println("Status code: " + e.getCode());
            System.err.println("Response headers: " + e.getResponseHeaders());
            System.err.println("Reason: " + e.getResponseBody());
            e.printStackTrace();
        }
    }
}
```

### Parameters


| Name | Type | Description  | Notes |
|------------- | ------------- | ------------- | -------------|
| **name** | **String**| The table name. | |
| **xEnvironment** | [**EnvironmentModel**](.md)| The environment whose physical table is addressed. PRODUCTION when omitted. | [optional] [enum: DEVELOPMENT, STAGING, PRODUCTION] |

### Return type

ApiResponse<[**ClearRowsResponseModel**](ClearRowsResponseModel.md)>


### Authorization

No authorization required

### HTTP request headers

- **Content-Type**: Not defined
- **Accept**: application/json, application/problem+json

### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
| **200** | How many rows were deleted. |  -  |
| **404** | The problem. |  -  |


## createRow

> DataTableRowModel createRow(name, createRowRequestModel, xEnvironment)

Insert a row

Insert one row.

### Example

```java
// Import classes:
import com.bytechef.cli.client.automationdatatable.ApiClient;
import com.bytechef.cli.client.automationdatatable.ApiException;
import com.bytechef.cli.client.automationdatatable.Configuration;
import com.bytechef.cli.client.automationdatatable.models.*;
import com.bytechef.cli.client.automationdatatable.api.DataTableRowApi;

public class Example {
    public static void main(String[] args) {
        ApiClient defaultClient = Configuration.getDefaultApiClient();
        defaultClient.setBasePath("/api/automation/v1");

        DataTableRowApi apiInstance = new DataTableRowApi(defaultClient);
        String name = "name_example"; // String | The table name.
        CreateRowRequestModel createRowRequestModel = new CreateRowRequestModel(); // CreateRowRequestModel | 
        EnvironmentModel xEnvironment = EnvironmentModel.fromValue("DEVELOPMENT"); // EnvironmentModel | The environment whose physical table is addressed. PRODUCTION when omitted.
        try {
            DataTableRowModel result = apiInstance.createRow(name, createRowRequestModel, xEnvironment);
            System.out.println(result);
        } catch (ApiException e) {
            System.err.println("Exception when calling DataTableRowApi#createRow");
            System.err.println("Status code: " + e.getCode());
            System.err.println("Reason: " + e.getResponseBody());
            System.err.println("Response headers: " + e.getResponseHeaders());
            e.printStackTrace();
        }
    }
}
```

### Parameters


| Name | Type | Description  | Notes |
|------------- | ------------- | ------------- | -------------|
| **name** | **String**| The table name. | |
| **createRowRequestModel** | [**CreateRowRequestModel**](CreateRowRequestModel.md)|  | |
| **xEnvironment** | [**EnvironmentModel**](.md)| The environment whose physical table is addressed. PRODUCTION when omitted. | [optional] [enum: DEVELOPMENT, STAGING, PRODUCTION] |

### Return type

[**DataTableRowModel**](DataTableRowModel.md)


### Authorization

No authorization required

### HTTP request headers

- **Content-Type**: application/json
- **Accept**: application/json, application/problem+json

### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
| **201** | The inserted row. |  -  |
| **400** | The problem. |  -  |
| **404** | The problem. |  -  |
| **409** | The problem. |  -  |
| **507** | The problem. |  -  |

## createRowWithHttpInfo

> ApiResponse<DataTableRowModel> createRowWithHttpInfo(name, createRowRequestModel, xEnvironment)

Insert a row

Insert one row.

### Example

```java
// Import classes:
import com.bytechef.cli.client.automationdatatable.ApiClient;
import com.bytechef.cli.client.automationdatatable.ApiException;
import com.bytechef.cli.client.automationdatatable.ApiResponse;
import com.bytechef.cli.client.automationdatatable.Configuration;
import com.bytechef.cli.client.automationdatatable.models.*;
import com.bytechef.cli.client.automationdatatable.api.DataTableRowApi;

public class Example {
    public static void main(String[] args) {
        ApiClient defaultClient = Configuration.getDefaultApiClient();
        defaultClient.setBasePath("/api/automation/v1");

        DataTableRowApi apiInstance = new DataTableRowApi(defaultClient);
        String name = "name_example"; // String | The table name.
        CreateRowRequestModel createRowRequestModel = new CreateRowRequestModel(); // CreateRowRequestModel | 
        EnvironmentModel xEnvironment = EnvironmentModel.fromValue("DEVELOPMENT"); // EnvironmentModel | The environment whose physical table is addressed. PRODUCTION when omitted.
        try {
            ApiResponse<DataTableRowModel> response = apiInstance.createRowWithHttpInfo(name, createRowRequestModel, xEnvironment);
            System.out.println("Status code: " + response.getStatusCode());
            System.out.println("Response headers: " + response.getHeaders());
            System.out.println("Response body: " + response.getData());
        } catch (ApiException e) {
            System.err.println("Exception when calling DataTableRowApi#createRow");
            System.err.println("Status code: " + e.getCode());
            System.err.println("Response headers: " + e.getResponseHeaders());
            System.err.println("Reason: " + e.getResponseBody());
            e.printStackTrace();
        }
    }
}
```

### Parameters


| Name | Type | Description  | Notes |
|------------- | ------------- | ------------- | -------------|
| **name** | **String**| The table name. | |
| **createRowRequestModel** | [**CreateRowRequestModel**](CreateRowRequestModel.md)|  | |
| **xEnvironment** | [**EnvironmentModel**](.md)| The environment whose physical table is addressed. PRODUCTION when omitted. | [optional] [enum: DEVELOPMENT, STAGING, PRODUCTION] |

### Return type

ApiResponse<[**DataTableRowModel**](DataTableRowModel.md)>


### Authorization

No authorization required

### HTTP request headers

- **Content-Type**: application/json
- **Accept**: application/json, application/problem+json

### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
| **201** | The inserted row. |  -  |
| **400** | The problem. |  -  |
| **404** | The problem. |  -  |
| **409** | The problem. |  -  |
| **507** | The problem. |  -  |


## deleteRow

> void deleteRow(name, id, xEnvironment)

Delete a row

### Example

```java
// Import classes:
import com.bytechef.cli.client.automationdatatable.ApiClient;
import com.bytechef.cli.client.automationdatatable.ApiException;
import com.bytechef.cli.client.automationdatatable.Configuration;
import com.bytechef.cli.client.automationdatatable.models.*;
import com.bytechef.cli.client.automationdatatable.api.DataTableRowApi;

public class Example {
    public static void main(String[] args) {
        ApiClient defaultClient = Configuration.getDefaultApiClient();
        defaultClient.setBasePath("/api/automation/v1");

        DataTableRowApi apiInstance = new DataTableRowApi(defaultClient);
        String name = "name_example"; // String | The table name.
        Long id = 56L; // Long | The row id.
        EnvironmentModel xEnvironment = EnvironmentModel.fromValue("DEVELOPMENT"); // EnvironmentModel | The environment whose physical table is addressed. PRODUCTION when omitted.
        try {
            apiInstance.deleteRow(name, id, xEnvironment);
        } catch (ApiException e) {
            System.err.println("Exception when calling DataTableRowApi#deleteRow");
            System.err.println("Status code: " + e.getCode());
            System.err.println("Reason: " + e.getResponseBody());
            System.err.println("Response headers: " + e.getResponseHeaders());
            e.printStackTrace();
        }
    }
}
```

### Parameters


| Name | Type | Description  | Notes |
|------------- | ------------- | ------------- | -------------|
| **name** | **String**| The table name. | |
| **id** | **Long**| The row id. | |
| **xEnvironment** | [**EnvironmentModel**](.md)| The environment whose physical table is addressed. PRODUCTION when omitted. | [optional] [enum: DEVELOPMENT, STAGING, PRODUCTION] |

### Return type


null (empty response body)

### Authorization

No authorization required

### HTTP request headers

- **Content-Type**: Not defined
- **Accept**: application/problem+json

### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
| **204** | Deleted. |  -  |
| **404** | The problem. |  -  |

## deleteRowWithHttpInfo

> ApiResponse<Void> deleteRowWithHttpInfo(name, id, xEnvironment)

Delete a row

### Example

```java
// Import classes:
import com.bytechef.cli.client.automationdatatable.ApiClient;
import com.bytechef.cli.client.automationdatatable.ApiException;
import com.bytechef.cli.client.automationdatatable.ApiResponse;
import com.bytechef.cli.client.automationdatatable.Configuration;
import com.bytechef.cli.client.automationdatatable.models.*;
import com.bytechef.cli.client.automationdatatable.api.DataTableRowApi;

public class Example {
    public static void main(String[] args) {
        ApiClient defaultClient = Configuration.getDefaultApiClient();
        defaultClient.setBasePath("/api/automation/v1");

        DataTableRowApi apiInstance = new DataTableRowApi(defaultClient);
        String name = "name_example"; // String | The table name.
        Long id = 56L; // Long | The row id.
        EnvironmentModel xEnvironment = EnvironmentModel.fromValue("DEVELOPMENT"); // EnvironmentModel | The environment whose physical table is addressed. PRODUCTION when omitted.
        try {
            ApiResponse<Void> response = apiInstance.deleteRowWithHttpInfo(name, id, xEnvironment);
            System.out.println("Status code: " + response.getStatusCode());
            System.out.println("Response headers: " + response.getHeaders());
        } catch (ApiException e) {
            System.err.println("Exception when calling DataTableRowApi#deleteRow");
            System.err.println("Status code: " + e.getCode());
            System.err.println("Response headers: " + e.getResponseHeaders());
            System.err.println("Reason: " + e.getResponseBody());
            e.printStackTrace();
        }
    }
}
```

### Parameters


| Name | Type | Description  | Notes |
|------------- | ------------- | ------------- | -------------|
| **name** | **String**| The table name. | |
| **id** | **Long**| The row id. | |
| **xEnvironment** | [**EnvironmentModel**](.md)| The environment whose physical table is addressed. PRODUCTION when omitted. | [optional] [enum: DEVELOPMENT, STAGING, PRODUCTION] |

### Return type


ApiResponse<Void>

### Authorization

No authorization required

### HTTP request headers

- **Content-Type**: Not defined
- **Accept**: application/problem+json

### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
| **204** | Deleted. |  -  |
| **404** | The problem. |  -  |


## deleteRowByExternalId

> void deleteRowByExternalId(name, externalId, xEnvironment)

Delete a row by external id

### Example

```java
// Import classes:
import com.bytechef.cli.client.automationdatatable.ApiClient;
import com.bytechef.cli.client.automationdatatable.ApiException;
import com.bytechef.cli.client.automationdatatable.Configuration;
import com.bytechef.cli.client.automationdatatable.models.*;
import com.bytechef.cli.client.automationdatatable.api.DataTableRowApi;

public class Example {
    public static void main(String[] args) {
        ApiClient defaultClient = Configuration.getDefaultApiClient();
        defaultClient.setBasePath("/api/automation/v1");

        DataTableRowApi apiInstance = new DataTableRowApi(defaultClient);
        String name = "name_example"; // String | The table name.
        String externalId = "externalId_example"; // String | The caller-supplied row key.
        EnvironmentModel xEnvironment = EnvironmentModel.fromValue("DEVELOPMENT"); // EnvironmentModel | The environment whose physical table is addressed. PRODUCTION when omitted.
        try {
            apiInstance.deleteRowByExternalId(name, externalId, xEnvironment);
        } catch (ApiException e) {
            System.err.println("Exception when calling DataTableRowApi#deleteRowByExternalId");
            System.err.println("Status code: " + e.getCode());
            System.err.println("Reason: " + e.getResponseBody());
            System.err.println("Response headers: " + e.getResponseHeaders());
            e.printStackTrace();
        }
    }
}
```

### Parameters


| Name | Type | Description  | Notes |
|------------- | ------------- | ------------- | -------------|
| **name** | **String**| The table name. | |
| **externalId** | **String**| The caller-supplied row key. | |
| **xEnvironment** | [**EnvironmentModel**](.md)| The environment whose physical table is addressed. PRODUCTION when omitted. | [optional] [enum: DEVELOPMENT, STAGING, PRODUCTION] |

### Return type


null (empty response body)

### Authorization

No authorization required

### HTTP request headers

- **Content-Type**: Not defined
- **Accept**: application/problem+json

### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
| **204** | Deleted. |  -  |
| **404** | The problem. |  -  |

## deleteRowByExternalIdWithHttpInfo

> ApiResponse<Void> deleteRowByExternalIdWithHttpInfo(name, externalId, xEnvironment)

Delete a row by external id

### Example

```java
// Import classes:
import com.bytechef.cli.client.automationdatatable.ApiClient;
import com.bytechef.cli.client.automationdatatable.ApiException;
import com.bytechef.cli.client.automationdatatable.ApiResponse;
import com.bytechef.cli.client.automationdatatable.Configuration;
import com.bytechef.cli.client.automationdatatable.models.*;
import com.bytechef.cli.client.automationdatatable.api.DataTableRowApi;

public class Example {
    public static void main(String[] args) {
        ApiClient defaultClient = Configuration.getDefaultApiClient();
        defaultClient.setBasePath("/api/automation/v1");

        DataTableRowApi apiInstance = new DataTableRowApi(defaultClient);
        String name = "name_example"; // String | The table name.
        String externalId = "externalId_example"; // String | The caller-supplied row key.
        EnvironmentModel xEnvironment = EnvironmentModel.fromValue("DEVELOPMENT"); // EnvironmentModel | The environment whose physical table is addressed. PRODUCTION when omitted.
        try {
            ApiResponse<Void> response = apiInstance.deleteRowByExternalIdWithHttpInfo(name, externalId, xEnvironment);
            System.out.println("Status code: " + response.getStatusCode());
            System.out.println("Response headers: " + response.getHeaders());
        } catch (ApiException e) {
            System.err.println("Exception when calling DataTableRowApi#deleteRowByExternalId");
            System.err.println("Status code: " + e.getCode());
            System.err.println("Response headers: " + e.getResponseHeaders());
            System.err.println("Reason: " + e.getResponseBody());
            e.printStackTrace();
        }
    }
}
```

### Parameters


| Name | Type | Description  | Notes |
|------------- | ------------- | ------------- | -------------|
| **name** | **String**| The table name. | |
| **externalId** | **String**| The caller-supplied row key. | |
| **xEnvironment** | [**EnvironmentModel**](.md)| The environment whose physical table is addressed. PRODUCTION when omitted. | [optional] [enum: DEVELOPMENT, STAGING, PRODUCTION] |

### Return type


ApiResponse<Void>

### Authorization

No authorization required

### HTTP request headers

- **Content-Type**: Not defined
- **Accept**: application/problem+json

### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
| **204** | Deleted. |  -  |
| **404** | The problem. |  -  |


## deleteRows

> DeleteRowsResponseModel deleteRows(name, ids, xEnvironment)

Delete rows

Delete rows by id. &#x60;ids&#x60; is required; to empty a table use the clear operation.

### Example

```java
// Import classes:
import com.bytechef.cli.client.automationdatatable.ApiClient;
import com.bytechef.cli.client.automationdatatable.ApiException;
import com.bytechef.cli.client.automationdatatable.Configuration;
import com.bytechef.cli.client.automationdatatable.models.*;
import com.bytechef.cli.client.automationdatatable.api.DataTableRowApi;

public class Example {
    public static void main(String[] args) {
        ApiClient defaultClient = Configuration.getDefaultApiClient();
        defaultClient.setBasePath("/api/automation/v1");

        DataTableRowApi apiInstance = new DataTableRowApi(defaultClient);
        String name = "name_example"; // String | The table name.
        List<Long> ids = Arrays.asList(); // List<Long> | Comma-separated row ids; at most 1000.
        EnvironmentModel xEnvironment = EnvironmentModel.fromValue("DEVELOPMENT"); // EnvironmentModel | The environment whose physical table is addressed. PRODUCTION when omitted.
        try {
            DeleteRowsResponseModel result = apiInstance.deleteRows(name, ids, xEnvironment);
            System.out.println(result);
        } catch (ApiException e) {
            System.err.println("Exception when calling DataTableRowApi#deleteRows");
            System.err.println("Status code: " + e.getCode());
            System.err.println("Reason: " + e.getResponseBody());
            System.err.println("Response headers: " + e.getResponseHeaders());
            e.printStackTrace();
        }
    }
}
```

### Parameters


| Name | Type | Description  | Notes |
|------------- | ------------- | ------------- | -------------|
| **name** | **String**| The table name. | |
| **ids** | [**List&lt;Long&gt;**](Long.md)| Comma-separated row ids; at most 1000. | |
| **xEnvironment** | [**EnvironmentModel**](.md)| The environment whose physical table is addressed. PRODUCTION when omitted. | [optional] [enum: DEVELOPMENT, STAGING, PRODUCTION] |

### Return type

[**DeleteRowsResponseModel**](DeleteRowsResponseModel.md)


### Authorization

No authorization required

### HTTP request headers

- **Content-Type**: Not defined
- **Accept**: application/json, application/problem+json

### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
| **200** | What was deleted. |  -  |
| **400** | The problem. |  -  |
| **404** | The problem. |  -  |

## deleteRowsWithHttpInfo

> ApiResponse<DeleteRowsResponseModel> deleteRowsWithHttpInfo(name, ids, xEnvironment)

Delete rows

Delete rows by id. &#x60;ids&#x60; is required; to empty a table use the clear operation.

### Example

```java
// Import classes:
import com.bytechef.cli.client.automationdatatable.ApiClient;
import com.bytechef.cli.client.automationdatatable.ApiException;
import com.bytechef.cli.client.automationdatatable.ApiResponse;
import com.bytechef.cli.client.automationdatatable.Configuration;
import com.bytechef.cli.client.automationdatatable.models.*;
import com.bytechef.cli.client.automationdatatable.api.DataTableRowApi;

public class Example {
    public static void main(String[] args) {
        ApiClient defaultClient = Configuration.getDefaultApiClient();
        defaultClient.setBasePath("/api/automation/v1");

        DataTableRowApi apiInstance = new DataTableRowApi(defaultClient);
        String name = "name_example"; // String | The table name.
        List<Long> ids = Arrays.asList(); // List<Long> | Comma-separated row ids; at most 1000.
        EnvironmentModel xEnvironment = EnvironmentModel.fromValue("DEVELOPMENT"); // EnvironmentModel | The environment whose physical table is addressed. PRODUCTION when omitted.
        try {
            ApiResponse<DeleteRowsResponseModel> response = apiInstance.deleteRowsWithHttpInfo(name, ids, xEnvironment);
            System.out.println("Status code: " + response.getStatusCode());
            System.out.println("Response headers: " + response.getHeaders());
            System.out.println("Response body: " + response.getData());
        } catch (ApiException e) {
            System.err.println("Exception when calling DataTableRowApi#deleteRows");
            System.err.println("Status code: " + e.getCode());
            System.err.println("Response headers: " + e.getResponseHeaders());
            System.err.println("Reason: " + e.getResponseBody());
            e.printStackTrace();
        }
    }
}
```

### Parameters


| Name | Type | Description  | Notes |
|------------- | ------------- | ------------- | -------------|
| **name** | **String**| The table name. | |
| **ids** | [**List&lt;Long&gt;**](Long.md)| Comma-separated row ids; at most 1000. | |
| **xEnvironment** | [**EnvironmentModel**](.md)| The environment whose physical table is addressed. PRODUCTION when omitted. | [optional] [enum: DEVELOPMENT, STAGING, PRODUCTION] |

### Return type

ApiResponse<[**DeleteRowsResponseModel**](DeleteRowsResponseModel.md)>


### Authorization

No authorization required

### HTTP request headers

- **Content-Type**: Not defined
- **Accept**: application/json, application/problem+json

### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
| **200** | What was deleted. |  -  |
| **400** | The problem. |  -  |
| **404** | The problem. |  -  |


## exportRows

> String exportRows(name, xEnvironment)

Export rows as CSV

Export every row as CSV. &#x60;external_id&#x60; is the first column; &#x60;id&#x60; is not included.

### Example

```java
// Import classes:
import com.bytechef.cli.client.automationdatatable.ApiClient;
import com.bytechef.cli.client.automationdatatable.ApiException;
import com.bytechef.cli.client.automationdatatable.Configuration;
import com.bytechef.cli.client.automationdatatable.models.*;
import com.bytechef.cli.client.automationdatatable.api.DataTableRowApi;

public class Example {
    public static void main(String[] args) {
        ApiClient defaultClient = Configuration.getDefaultApiClient();
        defaultClient.setBasePath("/api/automation/v1");

        DataTableRowApi apiInstance = new DataTableRowApi(defaultClient);
        String name = "name_example"; // String | The table name.
        EnvironmentModel xEnvironment = EnvironmentModel.fromValue("DEVELOPMENT"); // EnvironmentModel | The environment whose physical table is addressed. PRODUCTION when omitted.
        try {
            String result = apiInstance.exportRows(name, xEnvironment);
            System.out.println(result);
        } catch (ApiException e) {
            System.err.println("Exception when calling DataTableRowApi#exportRows");
            System.err.println("Status code: " + e.getCode());
            System.err.println("Reason: " + e.getResponseBody());
            System.err.println("Response headers: " + e.getResponseHeaders());
            e.printStackTrace();
        }
    }
}
```

### Parameters


| Name | Type | Description  | Notes |
|------------- | ------------- | ------------- | -------------|
| **name** | **String**| The table name. | |
| **xEnvironment** | [**EnvironmentModel**](.md)| The environment whose physical table is addressed. PRODUCTION when omitted. | [optional] [enum: DEVELOPMENT, STAGING, PRODUCTION] |

### Return type

**String**


### Authorization

No authorization required

### HTTP request headers

- **Content-Type**: Not defined
- **Accept**: text/csv, application/problem+json

### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
| **200** | The CSV document. |  -  |
| **404** | The problem. |  -  |

## exportRowsWithHttpInfo

> ApiResponse<String> exportRowsWithHttpInfo(name, xEnvironment)

Export rows as CSV

Export every row as CSV. &#x60;external_id&#x60; is the first column; &#x60;id&#x60; is not included.

### Example

```java
// Import classes:
import com.bytechef.cli.client.automationdatatable.ApiClient;
import com.bytechef.cli.client.automationdatatable.ApiException;
import com.bytechef.cli.client.automationdatatable.ApiResponse;
import com.bytechef.cli.client.automationdatatable.Configuration;
import com.bytechef.cli.client.automationdatatable.models.*;
import com.bytechef.cli.client.automationdatatable.api.DataTableRowApi;

public class Example {
    public static void main(String[] args) {
        ApiClient defaultClient = Configuration.getDefaultApiClient();
        defaultClient.setBasePath("/api/automation/v1");

        DataTableRowApi apiInstance = new DataTableRowApi(defaultClient);
        String name = "name_example"; // String | The table name.
        EnvironmentModel xEnvironment = EnvironmentModel.fromValue("DEVELOPMENT"); // EnvironmentModel | The environment whose physical table is addressed. PRODUCTION when omitted.
        try {
            ApiResponse<String> response = apiInstance.exportRowsWithHttpInfo(name, xEnvironment);
            System.out.println("Status code: " + response.getStatusCode());
            System.out.println("Response headers: " + response.getHeaders());
            System.out.println("Response body: " + response.getData());
        } catch (ApiException e) {
            System.err.println("Exception when calling DataTableRowApi#exportRows");
            System.err.println("Status code: " + e.getCode());
            System.err.println("Response headers: " + e.getResponseHeaders());
            System.err.println("Reason: " + e.getResponseBody());
            e.printStackTrace();
        }
    }
}
```

### Parameters


| Name | Type | Description  | Notes |
|------------- | ------------- | ------------- | -------------|
| **name** | **String**| The table name. | |
| **xEnvironment** | [**EnvironmentModel**](.md)| The environment whose physical table is addressed. PRODUCTION when omitted. | [optional] [enum: DEVELOPMENT, STAGING, PRODUCTION] |

### Return type

ApiResponse<**String**>


### Authorization

No authorization required

### HTTP request headers

- **Content-Type**: Not defined
- **Accept**: text/csv, application/problem+json

### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
| **200** | The CSV document. |  -  |
| **404** | The problem. |  -  |


## getRow

> DataTableRowModel getRow(name, id, xEnvironment)

Get a row

### Example

```java
// Import classes:
import com.bytechef.cli.client.automationdatatable.ApiClient;
import com.bytechef.cli.client.automationdatatable.ApiException;
import com.bytechef.cli.client.automationdatatable.Configuration;
import com.bytechef.cli.client.automationdatatable.models.*;
import com.bytechef.cli.client.automationdatatable.api.DataTableRowApi;

public class Example {
    public static void main(String[] args) {
        ApiClient defaultClient = Configuration.getDefaultApiClient();
        defaultClient.setBasePath("/api/automation/v1");

        DataTableRowApi apiInstance = new DataTableRowApi(defaultClient);
        String name = "name_example"; // String | The table name.
        Long id = 56L; // Long | The row id.
        EnvironmentModel xEnvironment = EnvironmentModel.fromValue("DEVELOPMENT"); // EnvironmentModel | The environment whose physical table is addressed. PRODUCTION when omitted.
        try {
            DataTableRowModel result = apiInstance.getRow(name, id, xEnvironment);
            System.out.println(result);
        } catch (ApiException e) {
            System.err.println("Exception when calling DataTableRowApi#getRow");
            System.err.println("Status code: " + e.getCode());
            System.err.println("Reason: " + e.getResponseBody());
            System.err.println("Response headers: " + e.getResponseHeaders());
            e.printStackTrace();
        }
    }
}
```

### Parameters


| Name | Type | Description  | Notes |
|------------- | ------------- | ------------- | -------------|
| **name** | **String**| The table name. | |
| **id** | **Long**| The row id. | |
| **xEnvironment** | [**EnvironmentModel**](.md)| The environment whose physical table is addressed. PRODUCTION when omitted. | [optional] [enum: DEVELOPMENT, STAGING, PRODUCTION] |

### Return type

[**DataTableRowModel**](DataTableRowModel.md)


### Authorization

No authorization required

### HTTP request headers

- **Content-Type**: Not defined
- **Accept**: application/json, application/problem+json

### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
| **200** | The row. |  -  |
| **404** | The problem. |  -  |

## getRowWithHttpInfo

> ApiResponse<DataTableRowModel> getRowWithHttpInfo(name, id, xEnvironment)

Get a row

### Example

```java
// Import classes:
import com.bytechef.cli.client.automationdatatable.ApiClient;
import com.bytechef.cli.client.automationdatatable.ApiException;
import com.bytechef.cli.client.automationdatatable.ApiResponse;
import com.bytechef.cli.client.automationdatatable.Configuration;
import com.bytechef.cli.client.automationdatatable.models.*;
import com.bytechef.cli.client.automationdatatable.api.DataTableRowApi;

public class Example {
    public static void main(String[] args) {
        ApiClient defaultClient = Configuration.getDefaultApiClient();
        defaultClient.setBasePath("/api/automation/v1");

        DataTableRowApi apiInstance = new DataTableRowApi(defaultClient);
        String name = "name_example"; // String | The table name.
        Long id = 56L; // Long | The row id.
        EnvironmentModel xEnvironment = EnvironmentModel.fromValue("DEVELOPMENT"); // EnvironmentModel | The environment whose physical table is addressed. PRODUCTION when omitted.
        try {
            ApiResponse<DataTableRowModel> response = apiInstance.getRowWithHttpInfo(name, id, xEnvironment);
            System.out.println("Status code: " + response.getStatusCode());
            System.out.println("Response headers: " + response.getHeaders());
            System.out.println("Response body: " + response.getData());
        } catch (ApiException e) {
            System.err.println("Exception when calling DataTableRowApi#getRow");
            System.err.println("Status code: " + e.getCode());
            System.err.println("Response headers: " + e.getResponseHeaders());
            System.err.println("Reason: " + e.getResponseBody());
            e.printStackTrace();
        }
    }
}
```

### Parameters


| Name | Type | Description  | Notes |
|------------- | ------------- | ------------- | -------------|
| **name** | **String**| The table name. | |
| **id** | **Long**| The row id. | |
| **xEnvironment** | [**EnvironmentModel**](.md)| The environment whose physical table is addressed. PRODUCTION when omitted. | [optional] [enum: DEVELOPMENT, STAGING, PRODUCTION] |

### Return type

ApiResponse<[**DataTableRowModel**](DataTableRowModel.md)>


### Authorization

No authorization required

### HTTP request headers

- **Content-Type**: Not defined
- **Accept**: application/json, application/problem+json

### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
| **200** | The row. |  -  |
| **404** | The problem. |  -  |


## getRowByExternalId

> DataTableRowModel getRowByExternalId(name, externalId, xEnvironment)

Get a row by external id

### Example

```java
// Import classes:
import com.bytechef.cli.client.automationdatatable.ApiClient;
import com.bytechef.cli.client.automationdatatable.ApiException;
import com.bytechef.cli.client.automationdatatable.Configuration;
import com.bytechef.cli.client.automationdatatable.models.*;
import com.bytechef.cli.client.automationdatatable.api.DataTableRowApi;

public class Example {
    public static void main(String[] args) {
        ApiClient defaultClient = Configuration.getDefaultApiClient();
        defaultClient.setBasePath("/api/automation/v1");

        DataTableRowApi apiInstance = new DataTableRowApi(defaultClient);
        String name = "name_example"; // String | The table name.
        String externalId = "externalId_example"; // String | The caller-supplied row key.
        EnvironmentModel xEnvironment = EnvironmentModel.fromValue("DEVELOPMENT"); // EnvironmentModel | The environment whose physical table is addressed. PRODUCTION when omitted.
        try {
            DataTableRowModel result = apiInstance.getRowByExternalId(name, externalId, xEnvironment);
            System.out.println(result);
        } catch (ApiException e) {
            System.err.println("Exception when calling DataTableRowApi#getRowByExternalId");
            System.err.println("Status code: " + e.getCode());
            System.err.println("Reason: " + e.getResponseBody());
            System.err.println("Response headers: " + e.getResponseHeaders());
            e.printStackTrace();
        }
    }
}
```

### Parameters


| Name | Type | Description  | Notes |
|------------- | ------------- | ------------- | -------------|
| **name** | **String**| The table name. | |
| **externalId** | **String**| The caller-supplied row key. | |
| **xEnvironment** | [**EnvironmentModel**](.md)| The environment whose physical table is addressed. PRODUCTION when omitted. | [optional] [enum: DEVELOPMENT, STAGING, PRODUCTION] |

### Return type

[**DataTableRowModel**](DataTableRowModel.md)


### Authorization

No authorization required

### HTTP request headers

- **Content-Type**: Not defined
- **Accept**: application/json, application/problem+json

### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
| **200** | The row. |  -  |
| **404** | The problem. |  -  |

## getRowByExternalIdWithHttpInfo

> ApiResponse<DataTableRowModel> getRowByExternalIdWithHttpInfo(name, externalId, xEnvironment)

Get a row by external id

### Example

```java
// Import classes:
import com.bytechef.cli.client.automationdatatable.ApiClient;
import com.bytechef.cli.client.automationdatatable.ApiException;
import com.bytechef.cli.client.automationdatatable.ApiResponse;
import com.bytechef.cli.client.automationdatatable.Configuration;
import com.bytechef.cli.client.automationdatatable.models.*;
import com.bytechef.cli.client.automationdatatable.api.DataTableRowApi;

public class Example {
    public static void main(String[] args) {
        ApiClient defaultClient = Configuration.getDefaultApiClient();
        defaultClient.setBasePath("/api/automation/v1");

        DataTableRowApi apiInstance = new DataTableRowApi(defaultClient);
        String name = "name_example"; // String | The table name.
        String externalId = "externalId_example"; // String | The caller-supplied row key.
        EnvironmentModel xEnvironment = EnvironmentModel.fromValue("DEVELOPMENT"); // EnvironmentModel | The environment whose physical table is addressed. PRODUCTION when omitted.
        try {
            ApiResponse<DataTableRowModel> response = apiInstance.getRowByExternalIdWithHttpInfo(name, externalId, xEnvironment);
            System.out.println("Status code: " + response.getStatusCode());
            System.out.println("Response headers: " + response.getHeaders());
            System.out.println("Response body: " + response.getData());
        } catch (ApiException e) {
            System.err.println("Exception when calling DataTableRowApi#getRowByExternalId");
            System.err.println("Status code: " + e.getCode());
            System.err.println("Response headers: " + e.getResponseHeaders());
            System.err.println("Reason: " + e.getResponseBody());
            e.printStackTrace();
        }
    }
}
```

### Parameters


| Name | Type | Description  | Notes |
|------------- | ------------- | ------------- | -------------|
| **name** | **String**| The table name. | |
| **externalId** | **String**| The caller-supplied row key. | |
| **xEnvironment** | [**EnvironmentModel**](.md)| The environment whose physical table is addressed. PRODUCTION when omitted. | [optional] [enum: DEVELOPMENT, STAGING, PRODUCTION] |

### Return type

ApiResponse<[**DataTableRowModel**](DataTableRowModel.md)>


### Authorization

No authorization required

### HTTP request headers

- **Content-Type**: Not defined
- **Accept**: application/json, application/problem+json

### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
| **200** | The row. |  -  |
| **404** | The problem. |  -  |


## importRows

> ImportRowsResponseModel importRows(name, body, xEnvironment)

Import rows from CSV

Insert rows from CSV. The header names existing columns; an optional &#x60;external_id&#x60; column sets each row&#39;s external id. Empty fields are null.

### Example

```java
// Import classes:
import com.bytechef.cli.client.automationdatatable.ApiClient;
import com.bytechef.cli.client.automationdatatable.ApiException;
import com.bytechef.cli.client.automationdatatable.Configuration;
import com.bytechef.cli.client.automationdatatable.models.*;
import com.bytechef.cli.client.automationdatatable.api.DataTableRowApi;

public class Example {
    public static void main(String[] args) {
        ApiClient defaultClient = Configuration.getDefaultApiClient();
        defaultClient.setBasePath("/api/automation/v1");

        DataTableRowApi apiInstance = new DataTableRowApi(defaultClient);
        String name = "name_example"; // String | The table name.
        String body = "body_example"; // String | 
        EnvironmentModel xEnvironment = EnvironmentModel.fromValue("DEVELOPMENT"); // EnvironmentModel | The environment whose physical table is addressed. PRODUCTION when omitted.
        try {
            ImportRowsResponseModel result = apiInstance.importRows(name, body, xEnvironment);
            System.out.println(result);
        } catch (ApiException e) {
            System.err.println("Exception when calling DataTableRowApi#importRows");
            System.err.println("Status code: " + e.getCode());
            System.err.println("Reason: " + e.getResponseBody());
            System.err.println("Response headers: " + e.getResponseHeaders());
            e.printStackTrace();
        }
    }
}
```

### Parameters


| Name | Type | Description  | Notes |
|------------- | ------------- | ------------- | -------------|
| **name** | **String**| The table name. | |
| **body** | **String**|  | |
| **xEnvironment** | [**EnvironmentModel**](.md)| The environment whose physical table is addressed. PRODUCTION when omitted. | [optional] [enum: DEVELOPMENT, STAGING, PRODUCTION] |

### Return type

[**ImportRowsResponseModel**](ImportRowsResponseModel.md)


### Authorization

No authorization required

### HTTP request headers

- **Content-Type**: text/csv
- **Accept**: application/json, application/problem+json

### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
| **200** | How many rows were inserted. |  -  |
| **400** | The problem. |  -  |
| **404** | The problem. |  -  |
| **409** | The problem. |  -  |
| **507** | The problem. |  -  |

## importRowsWithHttpInfo

> ApiResponse<ImportRowsResponseModel> importRowsWithHttpInfo(name, body, xEnvironment)

Import rows from CSV

Insert rows from CSV. The header names existing columns; an optional &#x60;external_id&#x60; column sets each row&#39;s external id. Empty fields are null.

### Example

```java
// Import classes:
import com.bytechef.cli.client.automationdatatable.ApiClient;
import com.bytechef.cli.client.automationdatatable.ApiException;
import com.bytechef.cli.client.automationdatatable.ApiResponse;
import com.bytechef.cli.client.automationdatatable.Configuration;
import com.bytechef.cli.client.automationdatatable.models.*;
import com.bytechef.cli.client.automationdatatable.api.DataTableRowApi;

public class Example {
    public static void main(String[] args) {
        ApiClient defaultClient = Configuration.getDefaultApiClient();
        defaultClient.setBasePath("/api/automation/v1");

        DataTableRowApi apiInstance = new DataTableRowApi(defaultClient);
        String name = "name_example"; // String | The table name.
        String body = "body_example"; // String | 
        EnvironmentModel xEnvironment = EnvironmentModel.fromValue("DEVELOPMENT"); // EnvironmentModel | The environment whose physical table is addressed. PRODUCTION when omitted.
        try {
            ApiResponse<ImportRowsResponseModel> response = apiInstance.importRowsWithHttpInfo(name, body, xEnvironment);
            System.out.println("Status code: " + response.getStatusCode());
            System.out.println("Response headers: " + response.getHeaders());
            System.out.println("Response body: " + response.getData());
        } catch (ApiException e) {
            System.err.println("Exception when calling DataTableRowApi#importRows");
            System.err.println("Status code: " + e.getCode());
            System.err.println("Response headers: " + e.getResponseHeaders());
            System.err.println("Reason: " + e.getResponseBody());
            e.printStackTrace();
        }
    }
}
```

### Parameters


| Name | Type | Description  | Notes |
|------------- | ------------- | ------------- | -------------|
| **name** | **String**| The table name. | |
| **body** | **String**|  | |
| **xEnvironment** | [**EnvironmentModel**](.md)| The environment whose physical table is addressed. PRODUCTION when omitted. | [optional] [enum: DEVELOPMENT, STAGING, PRODUCTION] |

### Return type

ApiResponse<[**ImportRowsResponseModel**](ImportRowsResponseModel.md)>


### Authorization

No authorization required

### HTTP request headers

- **Content-Type**: text/csv
- **Accept**: application/json, application/problem+json

### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
| **200** | How many rows were inserted. |  -  |
| **400** | The problem. |  -  |
| **404** | The problem. |  -  |
| **409** | The problem. |  -  |
| **507** | The problem. |  -  |


## listRows

> PageModel listRows(name, xEnvironment, filter, sort, pageNumber, pageSize)

Query rows

Query rows. Filters are ANDed; each is &#x60;column:OPERATOR:value&#x60; with OPERATOR one of EQ, NEQ, IN, CONTAINS, STARTS_WITH, GT, GTE, LT, LTE, BETWEEN. IN and BETWEEN take comma-separated values. &#x60;id&#x60; and &#x60;externalId&#x60; may be filtered and sorted like any column. The &#x60;where&#x60; parameter is reserved for a future expression filter and must not be sent.

### Example

```java
// Import classes:
import com.bytechef.cli.client.automationdatatable.ApiClient;
import com.bytechef.cli.client.automationdatatable.ApiException;
import com.bytechef.cli.client.automationdatatable.Configuration;
import com.bytechef.cli.client.automationdatatable.models.*;
import com.bytechef.cli.client.automationdatatable.api.DataTableRowApi;

public class Example {
    public static void main(String[] args) {
        ApiClient defaultClient = Configuration.getDefaultApiClient();
        defaultClient.setBasePath("/api/automation/v1");

        DataTableRowApi apiInstance = new DataTableRowApi(defaultClient);
        String name = "name_example"; // String | The table name.
        EnvironmentModel xEnvironment = EnvironmentModel.fromValue("DEVELOPMENT"); // EnvironmentModel | The environment whose physical table is addressed. PRODUCTION when omitted.
        List<String> filter = Arrays.asList(); // List<String> | A `column:OPERATOR:value` condition. Repeat the parameter to add conditions.
        List<String> sort = Arrays.asList(); // List<String> | A `column:ASC` or `column:DESC` ordering. Repeat to add tie-breakers; `id:ASC` is always last.
        Integer pageNumber = 0; // Integer | The zero-based page to return.
        Integer pageSize = 50; // Integer | Rows per page; at most 500.
        try {
            PageModel result = apiInstance.listRows(name, xEnvironment, filter, sort, pageNumber, pageSize);
            System.out.println(result);
        } catch (ApiException e) {
            System.err.println("Exception when calling DataTableRowApi#listRows");
            System.err.println("Status code: " + e.getCode());
            System.err.println("Reason: " + e.getResponseBody());
            System.err.println("Response headers: " + e.getResponseHeaders());
            e.printStackTrace();
        }
    }
}
```

### Parameters


| Name | Type | Description  | Notes |
|------------- | ------------- | ------------- | -------------|
| **name** | **String**| The table name. | |
| **xEnvironment** | [**EnvironmentModel**](.md)| The environment whose physical table is addressed. PRODUCTION when omitted. | [optional] [enum: DEVELOPMENT, STAGING, PRODUCTION] |
| **filter** | [**List&lt;String&gt;**](String.md)| A &#x60;column:OPERATOR:value&#x60; condition. Repeat the parameter to add conditions. | [optional] |
| **sort** | [**List&lt;String&gt;**](String.md)| A &#x60;column:ASC&#x60; or &#x60;column:DESC&#x60; ordering. Repeat to add tie-breakers; &#x60;id:ASC&#x60; is always last. | [optional] |
| **pageNumber** | **Integer**| The zero-based page to return. | [optional] [default to 0] |
| **pageSize** | **Integer**| Rows per page; at most 500. | [optional] [default to 50] |

### Return type

[**PageModel**](PageModel.md)


### Authorization

No authorization required

### HTTP request headers

- **Content-Type**: Not defined
- **Accept**: application/json, application/problem+json

### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
| **200** | The page of rows. |  -  |
| **400** | The problem. |  -  |
| **404** | The problem. |  -  |

## listRowsWithHttpInfo

> ApiResponse<PageModel> listRowsWithHttpInfo(name, xEnvironment, filter, sort, pageNumber, pageSize)

Query rows

Query rows. Filters are ANDed; each is &#x60;column:OPERATOR:value&#x60; with OPERATOR one of EQ, NEQ, IN, CONTAINS, STARTS_WITH, GT, GTE, LT, LTE, BETWEEN. IN and BETWEEN take comma-separated values. &#x60;id&#x60; and &#x60;externalId&#x60; may be filtered and sorted like any column. The &#x60;where&#x60; parameter is reserved for a future expression filter and must not be sent.

### Example

```java
// Import classes:
import com.bytechef.cli.client.automationdatatable.ApiClient;
import com.bytechef.cli.client.automationdatatable.ApiException;
import com.bytechef.cli.client.automationdatatable.ApiResponse;
import com.bytechef.cli.client.automationdatatable.Configuration;
import com.bytechef.cli.client.automationdatatable.models.*;
import com.bytechef.cli.client.automationdatatable.api.DataTableRowApi;

public class Example {
    public static void main(String[] args) {
        ApiClient defaultClient = Configuration.getDefaultApiClient();
        defaultClient.setBasePath("/api/automation/v1");

        DataTableRowApi apiInstance = new DataTableRowApi(defaultClient);
        String name = "name_example"; // String | The table name.
        EnvironmentModel xEnvironment = EnvironmentModel.fromValue("DEVELOPMENT"); // EnvironmentModel | The environment whose physical table is addressed. PRODUCTION when omitted.
        List<String> filter = Arrays.asList(); // List<String> | A `column:OPERATOR:value` condition. Repeat the parameter to add conditions.
        List<String> sort = Arrays.asList(); // List<String> | A `column:ASC` or `column:DESC` ordering. Repeat to add tie-breakers; `id:ASC` is always last.
        Integer pageNumber = 0; // Integer | The zero-based page to return.
        Integer pageSize = 50; // Integer | Rows per page; at most 500.
        try {
            ApiResponse<PageModel> response = apiInstance.listRowsWithHttpInfo(name, xEnvironment, filter, sort, pageNumber, pageSize);
            System.out.println("Status code: " + response.getStatusCode());
            System.out.println("Response headers: " + response.getHeaders());
            System.out.println("Response body: " + response.getData());
        } catch (ApiException e) {
            System.err.println("Exception when calling DataTableRowApi#listRows");
            System.err.println("Status code: " + e.getCode());
            System.err.println("Response headers: " + e.getResponseHeaders());
            System.err.println("Reason: " + e.getResponseBody());
            e.printStackTrace();
        }
    }
}
```

### Parameters


| Name | Type | Description  | Notes |
|------------- | ------------- | ------------- | -------------|
| **name** | **String**| The table name. | |
| **xEnvironment** | [**EnvironmentModel**](.md)| The environment whose physical table is addressed. PRODUCTION when omitted. | [optional] [enum: DEVELOPMENT, STAGING, PRODUCTION] |
| **filter** | [**List&lt;String&gt;**](String.md)| A &#x60;column:OPERATOR:value&#x60; condition. Repeat the parameter to add conditions. | [optional] |
| **sort** | [**List&lt;String&gt;**](String.md)| A &#x60;column:ASC&#x60; or &#x60;column:DESC&#x60; ordering. Repeat to add tie-breakers; &#x60;id:ASC&#x60; is always last. | [optional] |
| **pageNumber** | **Integer**| The zero-based page to return. | [optional] [default to 0] |
| **pageSize** | **Integer**| Rows per page; at most 500. | [optional] [default to 50] |

### Return type

ApiResponse<[**PageModel**](PageModel.md)>


### Authorization

No authorization required

### HTTP request headers

- **Content-Type**: Not defined
- **Accept**: application/json, application/problem+json

### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
| **200** | The page of rows. |  -  |
| **400** | The problem. |  -  |
| **404** | The problem. |  -  |


## updateRow

> DataTableRowModel updateRow(name, id, updateRowRequestModel, xEnvironment)

Update a row

Merge values into a row. Omitted columns are untouched; a null value clears a column. &#x60;externalId&#x60; may be set or cleared.

### Example

```java
// Import classes:
import com.bytechef.cli.client.automationdatatable.ApiClient;
import com.bytechef.cli.client.automationdatatable.ApiException;
import com.bytechef.cli.client.automationdatatable.Configuration;
import com.bytechef.cli.client.automationdatatable.models.*;
import com.bytechef.cli.client.automationdatatable.api.DataTableRowApi;

public class Example {
    public static void main(String[] args) {
        ApiClient defaultClient = Configuration.getDefaultApiClient();
        defaultClient.setBasePath("/api/automation/v1");

        DataTableRowApi apiInstance = new DataTableRowApi(defaultClient);
        String name = "name_example"; // String | The table name.
        Long id = 56L; // Long | The row id.
        UpdateRowRequestModel updateRowRequestModel = new UpdateRowRequestModel(); // UpdateRowRequestModel | 
        EnvironmentModel xEnvironment = EnvironmentModel.fromValue("DEVELOPMENT"); // EnvironmentModel | The environment whose physical table is addressed. PRODUCTION when omitted.
        try {
            DataTableRowModel result = apiInstance.updateRow(name, id, updateRowRequestModel, xEnvironment);
            System.out.println(result);
        } catch (ApiException e) {
            System.err.println("Exception when calling DataTableRowApi#updateRow");
            System.err.println("Status code: " + e.getCode());
            System.err.println("Reason: " + e.getResponseBody());
            System.err.println("Response headers: " + e.getResponseHeaders());
            e.printStackTrace();
        }
    }
}
```

### Parameters


| Name | Type | Description  | Notes |
|------------- | ------------- | ------------- | -------------|
| **name** | **String**| The table name. | |
| **id** | **Long**| The row id. | |
| **updateRowRequestModel** | [**UpdateRowRequestModel**](UpdateRowRequestModel.md)|  | |
| **xEnvironment** | [**EnvironmentModel**](.md)| The environment whose physical table is addressed. PRODUCTION when omitted. | [optional] [enum: DEVELOPMENT, STAGING, PRODUCTION] |

### Return type

[**DataTableRowModel**](DataTableRowModel.md)


### Authorization

No authorization required

### HTTP request headers

- **Content-Type**: application/json
- **Accept**: application/json, application/problem+json

### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
| **200** | The updated row. |  -  |
| **400** | The problem. |  -  |
| **404** | The problem. |  -  |
| **409** | The problem. |  -  |

## updateRowWithHttpInfo

> ApiResponse<DataTableRowModel> updateRowWithHttpInfo(name, id, updateRowRequestModel, xEnvironment)

Update a row

Merge values into a row. Omitted columns are untouched; a null value clears a column. &#x60;externalId&#x60; may be set or cleared.

### Example

```java
// Import classes:
import com.bytechef.cli.client.automationdatatable.ApiClient;
import com.bytechef.cli.client.automationdatatable.ApiException;
import com.bytechef.cli.client.automationdatatable.ApiResponse;
import com.bytechef.cli.client.automationdatatable.Configuration;
import com.bytechef.cli.client.automationdatatable.models.*;
import com.bytechef.cli.client.automationdatatable.api.DataTableRowApi;

public class Example {
    public static void main(String[] args) {
        ApiClient defaultClient = Configuration.getDefaultApiClient();
        defaultClient.setBasePath("/api/automation/v1");

        DataTableRowApi apiInstance = new DataTableRowApi(defaultClient);
        String name = "name_example"; // String | The table name.
        Long id = 56L; // Long | The row id.
        UpdateRowRequestModel updateRowRequestModel = new UpdateRowRequestModel(); // UpdateRowRequestModel | 
        EnvironmentModel xEnvironment = EnvironmentModel.fromValue("DEVELOPMENT"); // EnvironmentModel | The environment whose physical table is addressed. PRODUCTION when omitted.
        try {
            ApiResponse<DataTableRowModel> response = apiInstance.updateRowWithHttpInfo(name, id, updateRowRequestModel, xEnvironment);
            System.out.println("Status code: " + response.getStatusCode());
            System.out.println("Response headers: " + response.getHeaders());
            System.out.println("Response body: " + response.getData());
        } catch (ApiException e) {
            System.err.println("Exception when calling DataTableRowApi#updateRow");
            System.err.println("Status code: " + e.getCode());
            System.err.println("Response headers: " + e.getResponseHeaders());
            System.err.println("Reason: " + e.getResponseBody());
            e.printStackTrace();
        }
    }
}
```

### Parameters


| Name | Type | Description  | Notes |
|------------- | ------------- | ------------- | -------------|
| **name** | **String**| The table name. | |
| **id** | **Long**| The row id. | |
| **updateRowRequestModel** | [**UpdateRowRequestModel**](UpdateRowRequestModel.md)|  | |
| **xEnvironment** | [**EnvironmentModel**](.md)| The environment whose physical table is addressed. PRODUCTION when omitted. | [optional] [enum: DEVELOPMENT, STAGING, PRODUCTION] |

### Return type

ApiResponse<[**DataTableRowModel**](DataTableRowModel.md)>


### Authorization

No authorization required

### HTTP request headers

- **Content-Type**: application/json
- **Accept**: application/json, application/problem+json

### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
| **200** | The updated row. |  -  |
| **400** | The problem. |  -  |
| **404** | The problem. |  -  |
| **409** | The problem. |  -  |


## upsertRowByExternalId

> DataTableRowModel upsertRowByExternalId(name, externalId, upsertRowRequestModel, xEnvironment)

Upsert a row by external id

Upsert: insert the row under this external id, or merge the values into the row that already carries it. Idempotent.

### Example

```java
// Import classes:
import com.bytechef.cli.client.automationdatatable.ApiClient;
import com.bytechef.cli.client.automationdatatable.ApiException;
import com.bytechef.cli.client.automationdatatable.Configuration;
import com.bytechef.cli.client.automationdatatable.models.*;
import com.bytechef.cli.client.automationdatatable.api.DataTableRowApi;

public class Example {
    public static void main(String[] args) {
        ApiClient defaultClient = Configuration.getDefaultApiClient();
        defaultClient.setBasePath("/api/automation/v1");

        DataTableRowApi apiInstance = new DataTableRowApi(defaultClient);
        String name = "name_example"; // String | The table name.
        String externalId = "externalId_example"; // String | The caller-supplied row key.
        UpsertRowRequestModel upsertRowRequestModel = new UpsertRowRequestModel(); // UpsertRowRequestModel | 
        EnvironmentModel xEnvironment = EnvironmentModel.fromValue("DEVELOPMENT"); // EnvironmentModel | The environment whose physical table is addressed. PRODUCTION when omitted.
        try {
            DataTableRowModel result = apiInstance.upsertRowByExternalId(name, externalId, upsertRowRequestModel, xEnvironment);
            System.out.println(result);
        } catch (ApiException e) {
            System.err.println("Exception when calling DataTableRowApi#upsertRowByExternalId");
            System.err.println("Status code: " + e.getCode());
            System.err.println("Reason: " + e.getResponseBody());
            System.err.println("Response headers: " + e.getResponseHeaders());
            e.printStackTrace();
        }
    }
}
```

### Parameters


| Name | Type | Description  | Notes |
|------------- | ------------- | ------------- | -------------|
| **name** | **String**| The table name. | |
| **externalId** | **String**| The caller-supplied row key. | |
| **upsertRowRequestModel** | [**UpsertRowRequestModel**](UpsertRowRequestModel.md)|  | |
| **xEnvironment** | [**EnvironmentModel**](.md)| The environment whose physical table is addressed. PRODUCTION when omitted. | [optional] [enum: DEVELOPMENT, STAGING, PRODUCTION] |

### Return type

[**DataTableRowModel**](DataTableRowModel.md)


### Authorization

No authorization required

### HTTP request headers

- **Content-Type**: application/json
- **Accept**: application/json, application/problem+json

### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
| **200** | The row existed and was updated. |  -  |
| **201** | The row was created. |  -  |
| **400** | The problem. |  -  |
| **404** | The problem. |  -  |
| **507** | The problem. |  -  |

## upsertRowByExternalIdWithHttpInfo

> ApiResponse<DataTableRowModel> upsertRowByExternalIdWithHttpInfo(name, externalId, upsertRowRequestModel, xEnvironment)

Upsert a row by external id

Upsert: insert the row under this external id, or merge the values into the row that already carries it. Idempotent.

### Example

```java
// Import classes:
import com.bytechef.cli.client.automationdatatable.ApiClient;
import com.bytechef.cli.client.automationdatatable.ApiException;
import com.bytechef.cli.client.automationdatatable.ApiResponse;
import com.bytechef.cli.client.automationdatatable.Configuration;
import com.bytechef.cli.client.automationdatatable.models.*;
import com.bytechef.cli.client.automationdatatable.api.DataTableRowApi;

public class Example {
    public static void main(String[] args) {
        ApiClient defaultClient = Configuration.getDefaultApiClient();
        defaultClient.setBasePath("/api/automation/v1");

        DataTableRowApi apiInstance = new DataTableRowApi(defaultClient);
        String name = "name_example"; // String | The table name.
        String externalId = "externalId_example"; // String | The caller-supplied row key.
        UpsertRowRequestModel upsertRowRequestModel = new UpsertRowRequestModel(); // UpsertRowRequestModel | 
        EnvironmentModel xEnvironment = EnvironmentModel.fromValue("DEVELOPMENT"); // EnvironmentModel | The environment whose physical table is addressed. PRODUCTION when omitted.
        try {
            ApiResponse<DataTableRowModel> response = apiInstance.upsertRowByExternalIdWithHttpInfo(name, externalId, upsertRowRequestModel, xEnvironment);
            System.out.println("Status code: " + response.getStatusCode());
            System.out.println("Response headers: " + response.getHeaders());
            System.out.println("Response body: " + response.getData());
        } catch (ApiException e) {
            System.err.println("Exception when calling DataTableRowApi#upsertRowByExternalId");
            System.err.println("Status code: " + e.getCode());
            System.err.println("Response headers: " + e.getResponseHeaders());
            System.err.println("Reason: " + e.getResponseBody());
            e.printStackTrace();
        }
    }
}
```

### Parameters


| Name | Type | Description  | Notes |
|------------- | ------------- | ------------- | -------------|
| **name** | **String**| The table name. | |
| **externalId** | **String**| The caller-supplied row key. | |
| **upsertRowRequestModel** | [**UpsertRowRequestModel**](UpsertRowRequestModel.md)|  | |
| **xEnvironment** | [**EnvironmentModel**](.md)| The environment whose physical table is addressed. PRODUCTION when omitted. | [optional] [enum: DEVELOPMENT, STAGING, PRODUCTION] |

### Return type

ApiResponse<[**DataTableRowModel**](DataTableRowModel.md)>


### Authorization

No authorization required

### HTTP request headers

- **Content-Type**: application/json
- **Accept**: application/json, application/problem+json

### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
| **200** | The row existed and was updated. |  -  |
| **201** | The row was created. |  -  |
| **400** | The problem. |  -  |
| **404** | The problem. |  -  |
| **507** | The problem. |  -  |

