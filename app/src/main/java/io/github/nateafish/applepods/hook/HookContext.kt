package io.github.nateafish.applepods.hook

import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Method
import java.lang.reflect.Constructor

abstract class HookContext {
    lateinit var module: XposedModule
    lateinit var appClassLoader: ClassLoader

    abstract fun onHook()

    fun findClass(name: String): Class<*> = Class.forName(name, false, appClassLoader)

    fun hookAfter(method: Method, block: HookParam.() -> Unit) {
        module.hook(method).intercept { chain ->
            val result = chain.proceed()
            HookParam(chain, result).apply(block).result
        }
    }

    fun hookAfter(constructor: Constructor<*>, block: HookParam.() -> Unit) {
        module.hook(constructor).intercept { chain ->
            val result = chain.proceed()
            HookParam(chain, result).apply(block).result
        }
    }

    fun hookBefore(method: Method, block: HookParam.() -> Unit) {
        module.hook(method).intercept { chain ->
            val param = HookParam(chain, null).apply(block)
            if (param.hasResult) param.result else chain.proceed()
        }
    }
}

object Log {
    @Volatile var module: XposedModule? = null

    fun i(tag: String, message: String) = module?.log(android.util.Log.INFO, tag, message)
    fun d(tag: String, message: String) = module?.log(android.util.Log.DEBUG, tag, message)
    fun e(tag: String, message: String, error: Throwable) =
        module?.log(android.util.Log.ERROR, tag, message, error)
}

class HookParam(private val chain: XposedInterface.Chain, initialResult: Any?) {
    val args: List<Any?> = chain.args
    val instance: Any? = chain.thisObject
    var hasResult = false
        private set
    var result: Any? = initialResult
        set(value) {
            hasResult = true
            field = value
        }
}

fun getObjectField(instance: Any?, fieldName: String): Any? {
    var type: Class<*>? = instance?.javaClass ?: return null
    while (type != null) {
        runCatching {
            return type.getDeclaredField(fieldName).apply { isAccessible = true }.get(instance)
        }
        type = type.superclass
    }
    throw NoSuchFieldException(fieldName)
}

fun callMethod(instance: Any?, methodName: String, vararg args: Any?): Any? {
    var type: Class<*>? = instance?.javaClass ?: return null
    while (type != null) {
        type.declaredMethods.firstOrNull {
            it.name == methodName && it.parameterTypes.size == args.size
        }?.let {
            it.isAccessible = true
            return it.invoke(instance, *args)
        }
        type = type.superclass
    }
    throw NoSuchMethodException(methodName)
}
