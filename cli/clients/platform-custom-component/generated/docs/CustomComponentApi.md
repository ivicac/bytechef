# CustomComponentApi

All URIs are relative to */api/platform/v1*

| Method | HTTP request | Description |
|------------- | ------------- | -------------|
| [**deployCustomComponent**](CustomComponentApi.md#deployCustomComponent) | **POST** /custom-components/deploy | Deploy a new custom component |
| [**deployCustomComponentWithHttpInfo**](CustomComponentApi.md#deployCustomComponentWithHttpInfo) | **POST** /custom-components/deploy | Deploy a new custom component |



## deployCustomComponent

> void deployCustomComponent(componentFile)

Deploy a new custom component

Deploy a new custom component.

### Example

```java
// Import classes:
import com.bytechef.cli.client.platformcustomcomponent.ApiClient;
import com.bytechef.cli.client.platformcustomcomponent.ApiException;
import com.bytechef.cli.client.platformcustomcomponent.Configuration;
import com.bytechef.cli.client.platformcustomcomponent.models.*;
import com.bytechef.cli.client.platformcustomcomponent.api.CustomComponentApi;

public class Example {
    public static void main(String[] args) {
        ApiClient defaultClient = Configuration.getDefaultApiClient();
        defaultClient.setBasePath("/api/platform/v1");

        CustomComponentApi apiInstance = new CustomComponentApi(defaultClient);
        File componentFile = new File("/path/to/file"); // File | The file of a custom component.
        try {
            apiInstance.deployCustomComponent(componentFile);
        } catch (ApiException e) {
            System.err.println("Exception when calling CustomComponentApi#deployCustomComponent");
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
| **componentFile** | **File**| The file of a custom component. | [optional] |

### Return type


null (empty response body)

### Authorization

No authorization required

### HTTP request headers

- **Content-Type**: multipart/form-data
- **Accept**: Not defined

### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
| **204** | Successful operation. |  -  |

## deployCustomComponentWithHttpInfo

> ApiResponse<Void> deployCustomComponentWithHttpInfo(componentFile)

Deploy a new custom component

Deploy a new custom component.

### Example

```java
// Import classes:
import com.bytechef.cli.client.platformcustomcomponent.ApiClient;
import com.bytechef.cli.client.platformcustomcomponent.ApiException;
import com.bytechef.cli.client.platformcustomcomponent.ApiResponse;
import com.bytechef.cli.client.platformcustomcomponent.Configuration;
import com.bytechef.cli.client.platformcustomcomponent.models.*;
import com.bytechef.cli.client.platformcustomcomponent.api.CustomComponentApi;

public class Example {
    public static void main(String[] args) {
        ApiClient defaultClient = Configuration.getDefaultApiClient();
        defaultClient.setBasePath("/api/platform/v1");

        CustomComponentApi apiInstance = new CustomComponentApi(defaultClient);
        File componentFile = new File("/path/to/file"); // File | The file of a custom component.
        try {
            ApiResponse<Void> response = apiInstance.deployCustomComponentWithHttpInfo(componentFile);
            System.out.println("Status code: " + response.getStatusCode());
            System.out.println("Response headers: " + response.getHeaders());
        } catch (ApiException e) {
            System.err.println("Exception when calling CustomComponentApi#deployCustomComponent");
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
| **componentFile** | **File**| The file of a custom component. | [optional] |

### Return type


ApiResponse<Void>

### Authorization

No authorization required

### HTTP request headers

- **Content-Type**: multipart/form-data
- **Accept**: Not defined

### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
| **204** | Successful operation. |  -  |

