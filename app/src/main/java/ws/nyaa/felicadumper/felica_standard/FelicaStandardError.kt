package ws.nyaa.felicadumper.felica_standard

import java.io.IOException
import java.util.Locale

class FelicaProtocolException(message: String) : IOException(message)

class FelicaStatusException(
    command: String,
    statusFlag1: Int,
    statusFlag2: Int,
) : IOException(
    "$command failed with status ${
        String.format(Locale.US, "0x%02X", statusFlag1 and 0xFF)
    }/${
        String.format(Locale.US, "0x%02X", statusFlag2 and 0xFF)
    }"
)
