package moe.chenxy.oppopods.pods

/** True only for MOONDROP PUDDING / MD-TWS-056 device names. */
fun isPuddingDeviceName(name: String?): Boolean {
    val normalized = name.orEmpty().lowercase().replace("-", "").replace("_", "").replace(" ", "")
    return normalized.contains("pudding") || normalized.contains("mdtws056")
}
