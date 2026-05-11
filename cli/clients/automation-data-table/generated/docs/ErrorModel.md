

# ErrorModel

An RFC 7807 problem detail. Branch on errorKey, never on detail.

## Properties

| Name | Type | Description | Notes |
|------------ | ------------- | ------------- | -------------|
|**type** | **String** | A URI identifying the problem type. |  [optional] |
|**title** | **String** |  |  [optional] |
|**status** | **Integer** |  |  [optional] |
|**detail** | **String** | A human-readable explanation of this occurrence. |  [optional] |
|**errorKey** | **Integer** | The stable machine-readable key. 100 DATA_TABLE_NOT_FOUND, 103 DATA_TABLE_NAME_INVALID, 104 DATA_TABLE_ALREADY_EXISTS, 105 COLUMN_NOT_FOUND, 106 COLUMN_ALREADY_EXISTS, 107 COLUMN_NAME_INVALID, 108 ROW_NOT_FOUND, 109 ROW_VALUE_INVALID, 110 ROW_EXTERNAL_ID_CONFLICT, 111 ROW_EXTERNAL_ID_REQUIRED, 112 FILTER_INVALID, 113 SORT_INVALID, 114 BATCH_TOO_LARGE, 115 CSV_INVALID, 116 STORAGE_LIMIT_EXCEEDED. Absent on schema-validation failures. |  [optional] |
|**entityClass** | **String** |  |  [optional] |



