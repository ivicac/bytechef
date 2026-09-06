# DataTableApi

All URIs are relative to */api/automation/v1*

| Method | HTTP request | Description |
|------------- | ------------- | -------------|
| [**createColumn**](DataTableApi.md#createColumn) | **POST** /data-tables/{name}/columns | Add a column |
| [**createColumnWithHttpInfo**](DataTableApi.md#createColumnWithHttpInfo) | **POST** /data-tables/{name}/columns | Add a column |
| [**createDataTable**](DataTableApi.md#createDataTable) | **POST** /workspaces/{workspaceId}/data-tables | Create a data table |
| [**createDataTableWithHttpInfo**](DataTableApi.md#createDataTableWithHttpInfo) | **POST** /workspaces/{workspaceId}/data-tables | Create a data table |
| [**deleteColumn**](DataTableApi.md#deleteColumn) | **DELETE** /data-tables/{name}/columns/{column} | Delete a column |
| [**deleteColumnWithHttpInfo**](DataTableApi.md#deleteColumnWithHttpInfo) | **DELETE** /data-tables/{name}/columns/{column} | Delete a column |
| [**deleteDataTable**](DataTableApi.md#deleteDataTable) | **DELETE** /data-tables/{name} | Delete a data table |
| [**deleteDataTableWithHttpInfo**](DataTableApi.md#deleteDataTableWithHttpInfo) | **DELETE** /data-tables/{name} | Delete a data table |
| [**getDataTable**](DataTableApi.md#getDataTable) | **GET** /data-tables/{name} | Get a data table |
| [**getDataTableWithHttpInfo**](DataTableApi.md#getDataTableWithHttpInfo) | **GET** /data-tables/{name} | Get a data table |
| [**listDataTables**](DataTableApi.md#listDataTables) | **GET** /workspaces/{workspaceId}/data-tables | List data tables |
| [**listDataTablesWithHttpInfo**](DataTableApi.md#listDataTablesWithHttpInfo) | **GET** /workspaces/{workspaceId}/data-tables | List data tables |
| [**renameColumn**](DataTableApi.md#renameColumn) | **POST** /data-tables/{name}/columns/{column}/rename | Rename a column |
| [**renameColumnWithHttpInfo**](DataTableApi.md#renameColumnWithHttpInfo) | **POST** /data-tables/{name}/columns/{column}/rename | Rename a column |
| [**updateDataTable**](DataTableApi.md#updateDataTable) | **PATCH** /data-tables/{name} | Update a data table |
| [**updateDataTableWithHttpInfo**](DataTableApi.md#updateDataTableWithHttpInfo) | **PATCH** /data-tables/{name} | Update a data table |



## createColumn

> DataTableModel createColumn(name, createColumnRequestModel, xEnvironment)

Add a column

Add a column.

### Example

```java
// Import classes:
import com.bytechef.cli.client.automationdatatable.ApiClient;
import com.bytechef.cli.client.automationdatatable.ApiException;
import com.bytechef.cli.client.automationdatatable.Configuration;
import com.bytechef.cli.client.automationdatatable.models.*;
import com.bytechef.cli.client.automationdatatable.api.DataTableApi;

public class Example {
    public static void main(String[] args) {
        ApiClient defaultClient = Configuration.getDefaultApiClient();
        defaultClient.setBasePath("/api/automation/v1");

        DataTableApi apiInstance = new DataTableApi(defaultClient);
        String name = "name_example"; // String | The table name.
        CreateColumnRequestModel createColumnRequestModel = new CreateColumnRequestModel(); // CreateColumnRequestModel | 
        EnvironmentModel xEnvironment = EnvironmentModel.fromValue("DEVELOPMENT"); // EnvironmentModel | The environment whose physical table is addressed. PRODUCTION when omitted.
        try {
            DataTableModel result = apiInstance.createColumn(name, createColumnRequestModel, xEnvironment);
            System.out.println(result);
        } catch (ApiException e) {
            System.err.println("Exception when calling DataTableApi#createColumn");
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
| **createColumnRequestModel** | [**CreateColumnRequestModel**](CreateColumnRequestModel.md)|  | |
| **xEnvironment** | [**EnvironmentModel**](.md)| The environment whose physical table is addressed. PRODUCTION when omitted. | [optional] [enum: DEVELOPMENT, STAGING, PRODUCTION] |

### Return type

[**DataTableModel**](DataTableModel.md)


### Authorization

No authorization required

### HTTP request headers

- **Content-Type**: application/json
- **Accept**: application/json, application/problem+json

### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
| **201** | The data table with the new column. |  -  |
| **400** | The problem. |  -  |
| **404** | The problem. |  -  |
| **409** | The problem. |  -  |

## createColumnWithHttpInfo

> ApiResponse<DataTableModel> createColumnWithHttpInfo(name, createColumnRequestModel, xEnvironment)

Add a column

Add a column.

### Example

```java
// Import classes:
import com.bytechef.cli.client.automationdatatable.ApiClient;
import com.bytechef.cli.client.automationdatatable.ApiException;
import com.bytechef.cli.client.automationdatatable.ApiResponse;
import com.bytechef.cli.client.automationdatatable.Configuration;
import com.bytechef.cli.client.automationdatatable.models.*;
import com.bytechef.cli.client.automationdatatable.api.DataTableApi;

public class Example {
    public static void main(String[] args) {
        ApiClient defaultClient = Configuration.getDefaultApiClient();
        defaultClient.setBasePath("/api/automation/v1");

        DataTableApi apiInstance = new DataTableApi(defaultClient);
        String name = "name_example"; // String | The table name.
        CreateColumnRequestModel createColumnRequestModel = new CreateColumnRequestModel(); // CreateColumnRequestModel | 
        EnvironmentModel xEnvironment = EnvironmentModel.fromValue("DEVELOPMENT"); // EnvironmentModel | The environment whose physical table is addressed. PRODUCTION when omitted.
        try {
            ApiResponse<DataTableModel> response = apiInstance.createColumnWithHttpInfo(name, createColumnRequestModel, xEnvironment);
            System.out.println("Status code: " + response.getStatusCode());
            System.out.println("Response headers: " + response.getHeaders());
            System.out.println("Response body: " + response.getData());
        } catch (ApiException e) {
            System.err.println("Exception when calling DataTableApi#createColumn");
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
| **createColumnRequestModel** | [**CreateColumnRequestModel**](CreateColumnRequestModel.md)|  | |
| **xEnvironment** | [**EnvironmentModel**](.md)| The environment whose physical table is addressed. PRODUCTION when omitted. | [optional] [enum: DEVELOPMENT, STAGING, PRODUCTION] |

### Return type

ApiResponse<[**DataTableModel**](DataTableModel.md)>


### Authorization

No authorization required

### HTTP request headers

- **Content-Type**: application/json
- **Accept**: application/json, application/problem+json

### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
| **201** | The data table with the new column. |  -  |
| **400** | The problem. |  -  |
| **404** | The problem. |  -  |
| **409** | The problem. |  -  |


## createDataTable

> DataTableModel createDataTable(workspaceId, createDataTableRequestModel, xEnvironment)

Create a data table

Create a data table in the requested environment. The same name may exist in several environments; it is one logical table with one physical table per environment.

### Example

```java
// Import classes:
import com.bytechef.cli.client.automationdatatable.ApiClient;
import com.bytechef.cli.client.automationdatatable.ApiException;
import com.bytechef.cli.client.automationdatatable.Configuration;
import com.bytechef.cli.client.automationdatatable.models.*;
import com.bytechef.cli.client.automationdatatable.api.DataTableApi;

public class Example {
    public static void main(String[] args) {
        ApiClient defaultClient = Configuration.getDefaultApiClient();
        defaultClient.setBasePath("/api/automation/v1");

        DataTableApi apiInstance = new DataTableApi(defaultClient);
        Long workspaceId = 56L; // Long | The id of a workspace.
        CreateDataTableRequestModel createDataTableRequestModel = new CreateDataTableRequestModel(); // CreateDataTableRequestModel | 
        EnvironmentModel xEnvironment = EnvironmentModel.fromValue("DEVELOPMENT"); // EnvironmentModel | The environment whose physical table is addressed. PRODUCTION when omitted.
        try {
            DataTableModel result = apiInstance.createDataTable(workspaceId, createDataTableRequestModel, xEnvironment);
            System.out.println(result);
        } catch (ApiException e) {
            System.err.println("Exception when calling DataTableApi#createDataTable");
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
| **workspaceId** | **Long**| The id of a workspace. | |
| **createDataTableRequestModel** | [**CreateDataTableRequestModel**](CreateDataTableRequestModel.md)|  | |
| **xEnvironment** | [**EnvironmentModel**](.md)| The environment whose physical table is addressed. PRODUCTION when omitted. | [optional] [enum: DEVELOPMENT, STAGING, PRODUCTION] |

### Return type

[**DataTableModel**](DataTableModel.md)


### Authorization

No authorization required

### HTTP request headers

- **Content-Type**: application/json
- **Accept**: application/json, application/problem+json

### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
| **201** | The created data table. |  -  |
| **400** | The problem. |  -  |
| **403** | The problem. |  -  |
| **409** | The problem. |  -  |

## createDataTableWithHttpInfo

> ApiResponse<DataTableModel> createDataTableWithHttpInfo(workspaceId, createDataTableRequestModel, xEnvironment)

Create a data table

Create a data table in the requested environment. The same name may exist in several environments; it is one logical table with one physical table per environment.

### Example

```java
// Import classes:
import com.bytechef.cli.client.automationdatatable.ApiClient;
import com.bytechef.cli.client.automationdatatable.ApiException;
import com.bytechef.cli.client.automationdatatable.ApiResponse;
import com.bytechef.cli.client.automationdatatable.Configuration;
import com.bytechef.cli.client.automationdatatable.models.*;
import com.bytechef.cli.client.automationdatatable.api.DataTableApi;

public class Example {
    public static void main(String[] args) {
        ApiClient defaultClient = Configuration.getDefaultApiClient();
        defaultClient.setBasePath("/api/automation/v1");

        DataTableApi apiInstance = new DataTableApi(defaultClient);
        Long workspaceId = 56L; // Long | The id of a workspace.
        CreateDataTableRequestModel createDataTableRequestModel = new CreateDataTableRequestModel(); // CreateDataTableRequestModel | 
        EnvironmentModel xEnvironment = EnvironmentModel.fromValue("DEVELOPMENT"); // EnvironmentModel | The environment whose physical table is addressed. PRODUCTION when omitted.
        try {
            ApiResponse<DataTableModel> response = apiInstance.createDataTableWithHttpInfo(workspaceId, createDataTableRequestModel, xEnvironment);
            System.out.println("Status code: " + response.getStatusCode());
            System.out.println("Response headers: " + response.getHeaders());
            System.out.println("Response body: " + response.getData());
        } catch (ApiException e) {
            System.err.println("Exception when calling DataTableApi#createDataTable");
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
| **workspaceId** | **Long**| The id of a workspace. | |
| **createDataTableRequestModel** | [**CreateDataTableRequestModel**](CreateDataTableRequestModel.md)|  | |
| **xEnvironment** | [**EnvironmentModel**](.md)| The environment whose physical table is addressed. PRODUCTION when omitted. | [optional] [enum: DEVELOPMENT, STAGING, PRODUCTION] |

### Return type

ApiResponse<[**DataTableModel**](DataTableModel.md)>


### Authorization

No authorization required

### HTTP request headers

- **Content-Type**: application/json
- **Accept**: application/json, application/problem+json

### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
| **201** | The created data table. |  -  |
| **400** | The problem. |  -  |
| **403** | The problem. |  -  |
| **409** | The problem. |  -  |


## deleteColumn

> void deleteColumn(name, column, xEnvironment)

Delete a column

Drop a column and every value stored in it. Irreversible.

### Example

```java
// Import classes:
import com.bytechef.cli.client.automationdatatable.ApiClient;
import com.bytechef.cli.client.automationdatatable.ApiException;
import com.bytechef.cli.client.automationdatatable.Configuration;
import com.bytechef.cli.client.automationdatatable.models.*;
import com.bytechef.cli.client.automationdatatable.api.DataTableApi;

public class Example {
    public static void main(String[] args) {
        ApiClient defaultClient = Configuration.getDefaultApiClient();
        defaultClient.setBasePath("/api/automation/v1");

        DataTableApi apiInstance = new DataTableApi(defaultClient);
        String name = "name_example"; // String | The table name.
        String column = "column_example"; // String | The column name.
        EnvironmentModel xEnvironment = EnvironmentModel.fromValue("DEVELOPMENT"); // EnvironmentModel | The environment whose physical table is addressed. PRODUCTION when omitted.
        try {
            apiInstance.deleteColumn(name, column, xEnvironment);
        } catch (ApiException e) {
            System.err.println("Exception when calling DataTableApi#deleteColumn");
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
| **column** | **String**| The column name. | |
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

## deleteColumnWithHttpInfo

> ApiResponse<Void> deleteColumnWithHttpInfo(name, column, xEnvironment)

Delete a column

Drop a column and every value stored in it. Irreversible.

### Example

```java
// Import classes:
import com.bytechef.cli.client.automationdatatable.ApiClient;
import com.bytechef.cli.client.automationdatatable.ApiException;
import com.bytechef.cli.client.automationdatatable.ApiResponse;
import com.bytechef.cli.client.automationdatatable.Configuration;
import com.bytechef.cli.client.automationdatatable.models.*;
import com.bytechef.cli.client.automationdatatable.api.DataTableApi;

public class Example {
    public static void main(String[] args) {
        ApiClient defaultClient = Configuration.getDefaultApiClient();
        defaultClient.setBasePath("/api/automation/v1");

        DataTableApi apiInstance = new DataTableApi(defaultClient);
        String name = "name_example"; // String | The table name.
        String column = "column_example"; // String | The column name.
        EnvironmentModel xEnvironment = EnvironmentModel.fromValue("DEVELOPMENT"); // EnvironmentModel | The environment whose physical table is addressed. PRODUCTION when omitted.
        try {
            ApiResponse<Void> response = apiInstance.deleteColumnWithHttpInfo(name, column, xEnvironment);
            System.out.println("Status code: " + response.getStatusCode());
            System.out.println("Response headers: " + response.getHeaders());
        } catch (ApiException e) {
            System.err.println("Exception when calling DataTableApi#deleteColumn");
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
| **column** | **String**| The column name. | |
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


## deleteDataTable

> void deleteDataTable(name, xEnvironment)

Delete a data table

Drop the table in the requested environment, with all of its rows. The logical table is removed once its last environment is dropped. Irreversible.

### Example

```java
// Import classes:
import com.bytechef.cli.client.automationdatatable.ApiClient;
import com.bytechef.cli.client.automationdatatable.ApiException;
import com.bytechef.cli.client.automationdatatable.Configuration;
import com.bytechef.cli.client.automationdatatable.models.*;
import com.bytechef.cli.client.automationdatatable.api.DataTableApi;

public class Example {
    public static void main(String[] args) {
        ApiClient defaultClient = Configuration.getDefaultApiClient();
        defaultClient.setBasePath("/api/automation/v1");

        DataTableApi apiInstance = new DataTableApi(defaultClient);
        String name = "name_example"; // String | The table name.
        EnvironmentModel xEnvironment = EnvironmentModel.fromValue("DEVELOPMENT"); // EnvironmentModel | The environment whose physical table is addressed. PRODUCTION when omitted.
        try {
            apiInstance.deleteDataTable(name, xEnvironment);
        } catch (ApiException e) {
            System.err.println("Exception when calling DataTableApi#deleteDataTable");
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

## deleteDataTableWithHttpInfo

> ApiResponse<Void> deleteDataTableWithHttpInfo(name, xEnvironment)

Delete a data table

Drop the table in the requested environment, with all of its rows. The logical table is removed once its last environment is dropped. Irreversible.

### Example

```java
// Import classes:
import com.bytechef.cli.client.automationdatatable.ApiClient;
import com.bytechef.cli.client.automationdatatable.ApiException;
import com.bytechef.cli.client.automationdatatable.ApiResponse;
import com.bytechef.cli.client.automationdatatable.Configuration;
import com.bytechef.cli.client.automationdatatable.models.*;
import com.bytechef.cli.client.automationdatatable.api.DataTableApi;

public class Example {
    public static void main(String[] args) {
        ApiClient defaultClient = Configuration.getDefaultApiClient();
        defaultClient.setBasePath("/api/automation/v1");

        DataTableApi apiInstance = new DataTableApi(defaultClient);
        String name = "name_example"; // String | The table name.
        EnvironmentModel xEnvironment = EnvironmentModel.fromValue("DEVELOPMENT"); // EnvironmentModel | The environment whose physical table is addressed. PRODUCTION when omitted.
        try {
            ApiResponse<Void> response = apiInstance.deleteDataTableWithHttpInfo(name, xEnvironment);
            System.out.println("Status code: " + response.getStatusCode());
            System.out.println("Response headers: " + response.getHeaders());
        } catch (ApiException e) {
            System.err.println("Exception when calling DataTableApi#deleteDataTable");
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


## getDataTable

> DataTableModel getDataTable(name, xEnvironment)

Get a data table

Get a data table as it exists in the requested environment.

### Example

```java
// Import classes:
import com.bytechef.cli.client.automationdatatable.ApiClient;
import com.bytechef.cli.client.automationdatatable.ApiException;
import com.bytechef.cli.client.automationdatatable.Configuration;
import com.bytechef.cli.client.automationdatatable.models.*;
import com.bytechef.cli.client.automationdatatable.api.DataTableApi;

public class Example {
    public static void main(String[] args) {
        ApiClient defaultClient = Configuration.getDefaultApiClient();
        defaultClient.setBasePath("/api/automation/v1");

        DataTableApi apiInstance = new DataTableApi(defaultClient);
        String name = "name_example"; // String | The table name.
        EnvironmentModel xEnvironment = EnvironmentModel.fromValue("DEVELOPMENT"); // EnvironmentModel | The environment whose physical table is addressed. PRODUCTION when omitted.
        try {
            DataTableModel result = apiInstance.getDataTable(name, xEnvironment);
            System.out.println(result);
        } catch (ApiException e) {
            System.err.println("Exception when calling DataTableApi#getDataTable");
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

[**DataTableModel**](DataTableModel.md)


### Authorization

No authorization required

### HTTP request headers

- **Content-Type**: Not defined
- **Accept**: application/json, application/problem+json

### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
| **200** | The data table. |  -  |
| **404** | The problem. |  -  |

## getDataTableWithHttpInfo

> ApiResponse<DataTableModel> getDataTableWithHttpInfo(name, xEnvironment)

Get a data table

Get a data table as it exists in the requested environment.

### Example

```java
// Import classes:
import com.bytechef.cli.client.automationdatatable.ApiClient;
import com.bytechef.cli.client.automationdatatable.ApiException;
import com.bytechef.cli.client.automationdatatable.ApiResponse;
import com.bytechef.cli.client.automationdatatable.Configuration;
import com.bytechef.cli.client.automationdatatable.models.*;
import com.bytechef.cli.client.automationdatatable.api.DataTableApi;

public class Example {
    public static void main(String[] args) {
        ApiClient defaultClient = Configuration.getDefaultApiClient();
        defaultClient.setBasePath("/api/automation/v1");

        DataTableApi apiInstance = new DataTableApi(defaultClient);
        String name = "name_example"; // String | The table name.
        EnvironmentModel xEnvironment = EnvironmentModel.fromValue("DEVELOPMENT"); // EnvironmentModel | The environment whose physical table is addressed. PRODUCTION when omitted.
        try {
            ApiResponse<DataTableModel> response = apiInstance.getDataTableWithHttpInfo(name, xEnvironment);
            System.out.println("Status code: " + response.getStatusCode());
            System.out.println("Response headers: " + response.getHeaders());
            System.out.println("Response body: " + response.getData());
        } catch (ApiException e) {
            System.err.println("Exception when calling DataTableApi#getDataTable");
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

ApiResponse<[**DataTableModel**](DataTableModel.md)>


### Authorization

No authorization required

### HTTP request headers

- **Content-Type**: Not defined
- **Accept**: application/json, application/problem+json

### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
| **200** | The data table. |  -  |
| **404** | The problem. |  -  |


## listDataTables

> List<DataTableModel> listDataTables(workspaceId, xEnvironment, tag)

List data tables

List the data tables of a workspace that exist in the requested environment.

### Example

```java
// Import classes:
import com.bytechef.cli.client.automationdatatable.ApiClient;
import com.bytechef.cli.client.automationdatatable.ApiException;
import com.bytechef.cli.client.automationdatatable.Configuration;
import com.bytechef.cli.client.automationdatatable.models.*;
import com.bytechef.cli.client.automationdatatable.api.DataTableApi;

public class Example {
    public static void main(String[] args) {
        ApiClient defaultClient = Configuration.getDefaultApiClient();
        defaultClient.setBasePath("/api/automation/v1");

        DataTableApi apiInstance = new DataTableApi(defaultClient);
        Long workspaceId = 56L; // Long | The id of a workspace.
        EnvironmentModel xEnvironment = EnvironmentModel.fromValue("DEVELOPMENT"); // EnvironmentModel | The environment whose physical table is addressed. PRODUCTION when omitted.
        String tag = "tag_example"; // String | Return only tables carrying this tag.
        try {
            List<DataTableModel> result = apiInstance.listDataTables(workspaceId, xEnvironment, tag);
            System.out.println(result);
        } catch (ApiException e) {
            System.err.println("Exception when calling DataTableApi#listDataTables");
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
| **workspaceId** | **Long**| The id of a workspace. | |
| **xEnvironment** | [**EnvironmentModel**](.md)| The environment whose physical table is addressed. PRODUCTION when omitted. | [optional] [enum: DEVELOPMENT, STAGING, PRODUCTION] |
| **tag** | **String**| Return only tables carrying this tag. | [optional] |

### Return type

[**List&lt;DataTableModel&gt;**](DataTableModel.md)


### Authorization

No authorization required

### HTTP request headers

- **Content-Type**: Not defined
- **Accept**: application/json, application/problem+json

### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
| **200** | The data tables. |  -  |
| **403** | The problem. |  -  |

## listDataTablesWithHttpInfo

> ApiResponse<List<DataTableModel>> listDataTablesWithHttpInfo(workspaceId, xEnvironment, tag)

List data tables

List the data tables of a workspace that exist in the requested environment.

### Example

```java
// Import classes:
import com.bytechef.cli.client.automationdatatable.ApiClient;
import com.bytechef.cli.client.automationdatatable.ApiException;
import com.bytechef.cli.client.automationdatatable.ApiResponse;
import com.bytechef.cli.client.automationdatatable.Configuration;
import com.bytechef.cli.client.automationdatatable.models.*;
import com.bytechef.cli.client.automationdatatable.api.DataTableApi;

public class Example {
    public static void main(String[] args) {
        ApiClient defaultClient = Configuration.getDefaultApiClient();
        defaultClient.setBasePath("/api/automation/v1");

        DataTableApi apiInstance = new DataTableApi(defaultClient);
        Long workspaceId = 56L; // Long | The id of a workspace.
        EnvironmentModel xEnvironment = EnvironmentModel.fromValue("DEVELOPMENT"); // EnvironmentModel | The environment whose physical table is addressed. PRODUCTION when omitted.
        String tag = "tag_example"; // String | Return only tables carrying this tag.
        try {
            ApiResponse<List<DataTableModel>> response = apiInstance.listDataTablesWithHttpInfo(workspaceId, xEnvironment, tag);
            System.out.println("Status code: " + response.getStatusCode());
            System.out.println("Response headers: " + response.getHeaders());
            System.out.println("Response body: " + response.getData());
        } catch (ApiException e) {
            System.err.println("Exception when calling DataTableApi#listDataTables");
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
| **workspaceId** | **Long**| The id of a workspace. | |
| **xEnvironment** | [**EnvironmentModel**](.md)| The environment whose physical table is addressed. PRODUCTION when omitted. | [optional] [enum: DEVELOPMENT, STAGING, PRODUCTION] |
| **tag** | **String**| Return only tables carrying this tag. | [optional] |

### Return type

ApiResponse<[**List&lt;DataTableModel&gt;**](DataTableModel.md)>


### Authorization

No authorization required

### HTTP request headers

- **Content-Type**: Not defined
- **Accept**: application/json, application/problem+json

### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
| **200** | The data tables. |  -  |
| **403** | The problem. |  -  |


## renameColumn

> DataTableModel renameColumn(name, column, renameColumnRequestModel, xEnvironment)

Rename a column

Rename a column. Workflows and filters that name the old column stop matching.

### Example

```java
// Import classes:
import com.bytechef.cli.client.automationdatatable.ApiClient;
import com.bytechef.cli.client.automationdatatable.ApiException;
import com.bytechef.cli.client.automationdatatable.Configuration;
import com.bytechef.cli.client.automationdatatable.models.*;
import com.bytechef.cli.client.automationdatatable.api.DataTableApi;

public class Example {
    public static void main(String[] args) {
        ApiClient defaultClient = Configuration.getDefaultApiClient();
        defaultClient.setBasePath("/api/automation/v1");

        DataTableApi apiInstance = new DataTableApi(defaultClient);
        String name = "name_example"; // String | The table name.
        String column = "column_example"; // String | The column name.
        RenameColumnRequestModel renameColumnRequestModel = new RenameColumnRequestModel(); // RenameColumnRequestModel | 
        EnvironmentModel xEnvironment = EnvironmentModel.fromValue("DEVELOPMENT"); // EnvironmentModel | The environment whose physical table is addressed. PRODUCTION when omitted.
        try {
            DataTableModel result = apiInstance.renameColumn(name, column, renameColumnRequestModel, xEnvironment);
            System.out.println(result);
        } catch (ApiException e) {
            System.err.println("Exception when calling DataTableApi#renameColumn");
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
| **column** | **String**| The column name. | |
| **renameColumnRequestModel** | [**RenameColumnRequestModel**](RenameColumnRequestModel.md)|  | |
| **xEnvironment** | [**EnvironmentModel**](.md)| The environment whose physical table is addressed. PRODUCTION when omitted. | [optional] [enum: DEVELOPMENT, STAGING, PRODUCTION] |

### Return type

[**DataTableModel**](DataTableModel.md)


### Authorization

No authorization required

### HTTP request headers

- **Content-Type**: application/json
- **Accept**: application/json, application/problem+json

### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
| **200** | The data table with the renamed column. |  -  |
| **400** | The problem. |  -  |
| **404** | The problem. |  -  |
| **409** | The problem. |  -  |

## renameColumnWithHttpInfo

> ApiResponse<DataTableModel> renameColumnWithHttpInfo(name, column, renameColumnRequestModel, xEnvironment)

Rename a column

Rename a column. Workflows and filters that name the old column stop matching.

### Example

```java
// Import classes:
import com.bytechef.cli.client.automationdatatable.ApiClient;
import com.bytechef.cli.client.automationdatatable.ApiException;
import com.bytechef.cli.client.automationdatatable.ApiResponse;
import com.bytechef.cli.client.automationdatatable.Configuration;
import com.bytechef.cli.client.automationdatatable.models.*;
import com.bytechef.cli.client.automationdatatable.api.DataTableApi;

public class Example {
    public static void main(String[] args) {
        ApiClient defaultClient = Configuration.getDefaultApiClient();
        defaultClient.setBasePath("/api/automation/v1");

        DataTableApi apiInstance = new DataTableApi(defaultClient);
        String name = "name_example"; // String | The table name.
        String column = "column_example"; // String | The column name.
        RenameColumnRequestModel renameColumnRequestModel = new RenameColumnRequestModel(); // RenameColumnRequestModel | 
        EnvironmentModel xEnvironment = EnvironmentModel.fromValue("DEVELOPMENT"); // EnvironmentModel | The environment whose physical table is addressed. PRODUCTION when omitted.
        try {
            ApiResponse<DataTableModel> response = apiInstance.renameColumnWithHttpInfo(name, column, renameColumnRequestModel, xEnvironment);
            System.out.println("Status code: " + response.getStatusCode());
            System.out.println("Response headers: " + response.getHeaders());
            System.out.println("Response body: " + response.getData());
        } catch (ApiException e) {
            System.err.println("Exception when calling DataTableApi#renameColumn");
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
| **column** | **String**| The column name. | |
| **renameColumnRequestModel** | [**RenameColumnRequestModel**](RenameColumnRequestModel.md)|  | |
| **xEnvironment** | [**EnvironmentModel**](.md)| The environment whose physical table is addressed. PRODUCTION when omitted. | [optional] [enum: DEVELOPMENT, STAGING, PRODUCTION] |

### Return type

ApiResponse<[**DataTableModel**](DataTableModel.md)>


### Authorization

No authorization required

### HTTP request headers

- **Content-Type**: application/json
- **Accept**: application/json, application/problem+json

### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
| **200** | The data table with the renamed column. |  -  |
| **400** | The problem. |  -  |
| **404** | The problem. |  -  |
| **409** | The problem. |  -  |


## updateDataTable

> DataTableModel updateDataTable(name, updateDataTableRequestModel, xEnvironment)

Update a data table

Update the description and/or tags. Both are properties of the logical table and apply to every environment. Omitted fields are left untouched.

### Example

```java
// Import classes:
import com.bytechef.cli.client.automationdatatable.ApiClient;
import com.bytechef.cli.client.automationdatatable.ApiException;
import com.bytechef.cli.client.automationdatatable.Configuration;
import com.bytechef.cli.client.automationdatatable.models.*;
import com.bytechef.cli.client.automationdatatable.api.DataTableApi;

public class Example {
    public static void main(String[] args) {
        ApiClient defaultClient = Configuration.getDefaultApiClient();
        defaultClient.setBasePath("/api/automation/v1");

        DataTableApi apiInstance = new DataTableApi(defaultClient);
        String name = "name_example"; // String | The table name.
        UpdateDataTableRequestModel updateDataTableRequestModel = new UpdateDataTableRequestModel(); // UpdateDataTableRequestModel | 
        EnvironmentModel xEnvironment = EnvironmentModel.fromValue("DEVELOPMENT"); // EnvironmentModel | The environment whose physical table is addressed. PRODUCTION when omitted.
        try {
            DataTableModel result = apiInstance.updateDataTable(name, updateDataTableRequestModel, xEnvironment);
            System.out.println(result);
        } catch (ApiException e) {
            System.err.println("Exception when calling DataTableApi#updateDataTable");
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
| **updateDataTableRequestModel** | [**UpdateDataTableRequestModel**](UpdateDataTableRequestModel.md)|  | |
| **xEnvironment** | [**EnvironmentModel**](.md)| The environment whose physical table is addressed. PRODUCTION when omitted. | [optional] [enum: DEVELOPMENT, STAGING, PRODUCTION] |

### Return type

[**DataTableModel**](DataTableModel.md)


### Authorization

No authorization required

### HTTP request headers

- **Content-Type**: application/json
- **Accept**: application/json, application/problem+json

### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
| **200** | The updated data table. |  -  |
| **400** | The problem. |  -  |
| **404** | The problem. |  -  |

## updateDataTableWithHttpInfo

> ApiResponse<DataTableModel> updateDataTableWithHttpInfo(name, updateDataTableRequestModel, xEnvironment)

Update a data table

Update the description and/or tags. Both are properties of the logical table and apply to every environment. Omitted fields are left untouched.

### Example

```java
// Import classes:
import com.bytechef.cli.client.automationdatatable.ApiClient;
import com.bytechef.cli.client.automationdatatable.ApiException;
import com.bytechef.cli.client.automationdatatable.ApiResponse;
import com.bytechef.cli.client.automationdatatable.Configuration;
import com.bytechef.cli.client.automationdatatable.models.*;
import com.bytechef.cli.client.automationdatatable.api.DataTableApi;

public class Example {
    public static void main(String[] args) {
        ApiClient defaultClient = Configuration.getDefaultApiClient();
        defaultClient.setBasePath("/api/automation/v1");

        DataTableApi apiInstance = new DataTableApi(defaultClient);
        String name = "name_example"; // String | The table name.
        UpdateDataTableRequestModel updateDataTableRequestModel = new UpdateDataTableRequestModel(); // UpdateDataTableRequestModel | 
        EnvironmentModel xEnvironment = EnvironmentModel.fromValue("DEVELOPMENT"); // EnvironmentModel | The environment whose physical table is addressed. PRODUCTION when omitted.
        try {
            ApiResponse<DataTableModel> response = apiInstance.updateDataTableWithHttpInfo(name, updateDataTableRequestModel, xEnvironment);
            System.out.println("Status code: " + response.getStatusCode());
            System.out.println("Response headers: " + response.getHeaders());
            System.out.println("Response body: " + response.getData());
        } catch (ApiException e) {
            System.err.println("Exception when calling DataTableApi#updateDataTable");
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
| **updateDataTableRequestModel** | [**UpdateDataTableRequestModel**](UpdateDataTableRequestModel.md)|  | |
| **xEnvironment** | [**EnvironmentModel**](.md)| The environment whose physical table is addressed. PRODUCTION when omitted. | [optional] [enum: DEVELOPMENT, STAGING, PRODUCTION] |

### Return type

ApiResponse<[**DataTableModel**](DataTableModel.md)>


### Authorization

No authorization required

### HTTP request headers

- **Content-Type**: application/json
- **Accept**: application/json, application/problem+json

### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
| **200** | The updated data table. |  -  |
| **400** | The problem. |  -  |
| **404** | The problem. |  -  |

