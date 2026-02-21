package ws.nyaa.felicadumper.felica_standard

internal const val FS_POLLING_COMMAND_CODE = 0x00
internal const val FS_REQUEST_SERVICE_COMMAND_CODE = 0x02
internal const val FS_READ_WITHOUT_ENCRYPTION_COMMAND_CODE = 0x06
internal const val FS_SEARCH_SERVICE_CODE_COMMAND_CODE = 0x0A
internal const val FS_REQUEST_SYSTEM_CODE_COMMAND_CODE = 0x0C
internal const val FS_AUTHENTICATION1_COMMAND_CODE = 0x10
internal const val FS_AUTHENTICATION2_COMMAND_CODE = 0x12
internal const val FS_READ_COMMAND_CODE = 0x14
internal const val FS_REQUEST_SERVICE_V2_COMMAND_CODE = 0x32

internal const val FS_POLLING_RESPONSE_CODE = 0x01
internal const val FS_REQUEST_SERVICE_RESPONSE_CODE = 0x03
internal const val FS_READ_WITHOUT_ENCRYPTION_RESPONSE_CODE = 0x07
internal const val FS_SEARCH_SERVICE_CODE_RESPONSE_CODE = 0x0B
internal const val FS_REQUEST_SYSTEM_CODE_RESPONSE_CODE = 0x0D
internal const val FS_AUTHENTICATION1_RESPONSE_CODE = 0x11
internal const val FS_AUTHENTICATION2_RESPONSE_CODE = 0x13
internal const val FS_REQUEST_SERVICE_V2_RESPONSE_CODE = 0x33

internal const val FS_BLOCK_SIZE = 16
internal const val FS_DES_BLOCK_SIZE = 8
internal const val FS_MAX_SERVICE_CODES_PER_REQUEST = 0x20
