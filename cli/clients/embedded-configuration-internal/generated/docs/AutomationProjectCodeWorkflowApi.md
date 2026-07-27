# AutomationProjectCodeWorkflowApi

All URIs are relative to */api/embedded/internal*

| Method | HTTP request | Description |
|------------- | ------------- | -------------|
| [**deployAutomationProjectCodeWorkflow**](AutomationProjectCodeWorkflowApi.md#deployAutomationProjectCodeWorkflow) | **POST** /automation/projects/deploy | Deploy a new automation code workflow into the embedded catalog |
| [**deployAutomationProjectCodeWorkflowWithHttpInfo**](AutomationProjectCodeWorkflowApi.md#deployAutomationProjectCodeWorkflowWithHttpInfo) | **POST** /automation/projects/deploy | Deploy a new automation code workflow into the embedded catalog |



## deployAutomationProjectCodeWorkflow

> AutomationProjectCodeWorkflowDeployResultModel deployAutomationProjectCodeWorkflow(projectFile)

Deploy a new automation code workflow into the embedded catalog

Deploy a new automation code workflow into the embedded catalog.

### Example

```java
// Import classes:
import com.bytechef.cli.client.embeddedconfigurationinternal.ApiClient;
import com.bytechef.cli.client.embeddedconfigurationinternal.ApiException;
import com.bytechef.cli.client.embeddedconfigurationinternal.Configuration;
import com.bytechef.cli.client.embeddedconfigurationinternal.models.*;
import com.bytechef.cli.client.embeddedconfigurationinternal.api.AutomationProjectCodeWorkflowApi;

public class Example {
    public static void main(String[] args) {
        ApiClient defaultClient = Configuration.getDefaultApiClient();
        defaultClient.setBasePath("/api/embedded/internal");

        AutomationProjectCodeWorkflowApi apiInstance = new AutomationProjectCodeWorkflowApi(defaultClient);
        File projectFile = new File("/path/to/file"); // File | The file of a code-native automation project.
        try {
            AutomationProjectCodeWorkflowDeployResultModel result = apiInstance.deployAutomationProjectCodeWorkflow(projectFile);
            System.out.println(result);
        } catch (ApiException e) {
            System.err.println("Exception when calling AutomationProjectCodeWorkflowApi#deployAutomationProjectCodeWorkflow");
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
| **projectFile** | **File**| The file of a code-native automation project. | [optional] |

### Return type

[**AutomationProjectCodeWorkflowDeployResultModel**](AutomationProjectCodeWorkflowDeployResultModel.md)


### Authorization

No authorization required

### HTTP request headers

- **Content-Type**: multipart/form-data
- **Accept**: application/json

### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
| **200** | Successful operation. |  -  |

## deployAutomationProjectCodeWorkflowWithHttpInfo

> ApiResponse<AutomationProjectCodeWorkflowDeployResultModel> deployAutomationProjectCodeWorkflowWithHttpInfo(projectFile)

Deploy a new automation code workflow into the embedded catalog

Deploy a new automation code workflow into the embedded catalog.

### Example

```java
// Import classes:
import com.bytechef.cli.client.embeddedconfigurationinternal.ApiClient;
import com.bytechef.cli.client.embeddedconfigurationinternal.ApiException;
import com.bytechef.cli.client.embeddedconfigurationinternal.ApiResponse;
import com.bytechef.cli.client.embeddedconfigurationinternal.Configuration;
import com.bytechef.cli.client.embeddedconfigurationinternal.models.*;
import com.bytechef.cli.client.embeddedconfigurationinternal.api.AutomationProjectCodeWorkflowApi;

public class Example {
    public static void main(String[] args) {
        ApiClient defaultClient = Configuration.getDefaultApiClient();
        defaultClient.setBasePath("/api/embedded/internal");

        AutomationProjectCodeWorkflowApi apiInstance = new AutomationProjectCodeWorkflowApi(defaultClient);
        File projectFile = new File("/path/to/file"); // File | The file of a code-native automation project.
        try {
            ApiResponse<AutomationProjectCodeWorkflowDeployResultModel> response = apiInstance.deployAutomationProjectCodeWorkflowWithHttpInfo(projectFile);
            System.out.println("Status code: " + response.getStatusCode());
            System.out.println("Response headers: " + response.getHeaders());
            System.out.println("Response body: " + response.getData());
        } catch (ApiException e) {
            System.err.println("Exception when calling AutomationProjectCodeWorkflowApi#deployAutomationProjectCodeWorkflow");
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
| **projectFile** | **File**| The file of a code-native automation project. | [optional] |

### Return type

ApiResponse<[**AutomationProjectCodeWorkflowDeployResultModel**](AutomationProjectCodeWorkflowDeployResultModel.md)>


### Authorization

No authorization required

### HTTP request headers

- **Content-Type**: multipart/form-data
- **Accept**: application/json

### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
| **200** | Successful operation. |  -  |

