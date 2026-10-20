@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package com.localdoc.ai.platform

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import platform.CoreFoundation.CFDictionaryAddValue
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFMutableDictionaryRef
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFTypeRefVar
import platform.CoreFoundation.kCFBooleanTrue
import platform.CoreFoundation.kCFTypeDictionaryKeyCallBacks
import platform.CoreFoundation.kCFTypeDictionaryValueCallBacks
import platform.Foundation.CFBridgingRelease
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSData
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.create
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.errSecSuccess
import platform.Security.kSecAttrAccessible
import platform.Security.kSecAttrAccessibleWhenUnlockedThisDeviceOnly
import platform.Security.kSecAttrAccount
import platform.Security.kSecAttrService
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecMatchLimit
import platform.Security.kSecMatchLimitOne
import platform.Security.kSecReturnData
import platform.Security.kSecValueData

/**
 * iOS: Keychain, класс «generic password». Доступ только при разблокированном устройстве и только на
 * нём (`ThisDeviceOnly`): значения не уходят в iCloud Keychain и в резервные копии.
 */
actual class SecureStore actual constructor() {

    actual fun get(key: String): String? = memScoped {
        val query = baseQuery(key)
        CFDictionaryAddValue(query, kSecReturnData, kCFBooleanTrue)
        CFDictionaryAddValue(query, kSecMatchLimit, kSecMatchLimitOne)
        val result = alloc<CFTypeRefVar>()
        val status = SecItemCopyMatching(query, result.ptr)
        CFRelease(query)
        if (status != errSecSuccess) return@memScoped null
        val data = CFBridgingRelease(result.value) as? NSData ?: return@memScoped null
        NSString.create(data = data, encoding = NSUTF8StringEncoding)?.toString()
    }

    actual fun put(key: String, value: String) {
        remove(key)
        val query = baseQuery(key)
        val data = CFBridgingRetain(value.encodeToByteArray().toNSData())
        CFDictionaryAddValue(query, kSecValueData, data)
        CFDictionaryAddValue(query, kSecAttrAccessible, kSecAttrAccessibleWhenUnlockedThisDeviceOnly)
        SecItemAdd(query, null)
        CFRelease(data)
        CFRelease(query)
    }

    actual fun remove(key: String) {
        val query = baseQuery(key)
        SecItemDelete(query)
        CFRelease(query)
    }

    private fun baseQuery(key: String): CFMutableDictionaryRef? {
        val query = CFDictionaryCreateMutable(null, 0, kCFTypeDictionaryKeyCallBacks.ptr, kCFTypeDictionaryValueCallBacks.ptr)
        CFDictionaryAddValue(query, kSecClass, kSecClassGenericPassword)
        val service = CFBridgingRetain(SERVICE)
        CFDictionaryAddValue(query, kSecAttrService, service)
        CFRelease(service)
        val account = CFBridgingRetain(key)
        CFDictionaryAddValue(query, kSecAttrAccount, account)
        CFRelease(account)
        return query
    }

    private companion object {
        const val SERVICE = "com.localdoc.ai.secure"
    }
}
