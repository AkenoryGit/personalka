package probe

import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.processing.SymbolProcessorProvider
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSPropertyDeclaration
import com.google.devtools.ksp.symbol.KSType

class ProbeProvider : SymbolProcessorProvider {
    override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor = Probe(environment.logger)
}

class Probe(private val log: KSPLogger) : SymbolProcessor {
    private var done = false
    private val bad = mutableListOf<String>()

    private fun check(where: String, type: KSType?) {
        if (type == null || type.isError) bad += "$where -> ${type?.declaration?.qualifiedName?.asString()} [$type]"
    }

    private fun walkClass(prefix: String, c: KSClassDeclaration) {
        c.superTypes.forEach { check("$prefix super", it.resolve()) }
        c.annotations.forEach { a ->
            check("$prefix @${a.shortName.asString()}", a.annotationType.resolve())
            a.arguments.forEach { arg ->
                when (val v = arg.value) {
                    is KSType -> check("$prefix @${a.shortName.asString()}.${arg.name?.asString()}", v)
                    is List<*> -> v.forEach { item ->
                        if (item is KSType) check("$prefix @${a.shortName.asString()}.${arg.name?.asString()}[]", item)
                    }
                    else -> Unit
                }
            }
        }
        c.declarations.forEach { d ->
            when (d) {
                is KSFunctionDeclaration -> {
                    d.annotations.forEach { a -> check("$prefix fun ${d.simpleName.asString()} @${a.shortName.asString()}", a.annotationType.resolve()) }
                    check("$prefix fun ${d.simpleName.asString()} returns", d.returnType?.resolve())
                    d.parameters.forEach { p -> check("$prefix fun ${d.simpleName.asString()}(${p.name?.asString()})", p.type.resolve()) }
                }
                is KSPropertyDeclaration -> {
                    d.annotations.forEach { a -> check("$prefix val ${d.simpleName.asString()} @${a.shortName.asString()}", a.annotationType.resolve()) }
                    check("$prefix val ${d.simpleName.asString()}", d.type.resolve())
                }
                is KSClassDeclaration -> walkClass("$prefix.${d.simpleName.asString()}", d)
                else -> Unit
            }
        }
    }

    override fun process(resolver: Resolver): List<KSAnnotated> {
        if (done) return emptyList()
        done = true
        val names = listOf(
            "com.akenory.personalka.data.db.PersonalkaDatabase",
            "com.akenory.personalka.data.db.PersonalkaDatabaseConstructor",
            "com.akenory.personalka.data.db.Converters",
            "com.akenory.personalka.data.db.SyncFields",
            "com.akenory.personalka.data.db.entity.TrainingEntity",
            "com.akenory.personalka.data.db.entity.WaitEntryEntity",
            "com.akenory.personalka.data.db.entity.CoachSettingsEntity",
            "com.akenory.personalka.data.db.dao.TrainingDao",
            "com.akenory.personalka.data.db.dao.ClientDao",
            "com.akenory.personalka.data.db.dao.CoachDao",
            "com.akenory.personalka.data.db.dao.ReferenceDao",
            "com.akenory.personalka.data.db.dao.ScheduleDao",
            "com.akenory.personalka.data.db.entity.ClientEntity",
            "com.akenory.personalka.data.db.entity.SeriesClientEntity",
        )
        names.forEach { name ->
            val c = resolver.getClassDeclarationByName(resolver.getKSNameFromString(name))
            if (c == null) bad += "NOT FOUND $name" else walkClass(name.substringAfterLast('.'), c)
        }
        log.warn("[probe] " + if (bad.isEmpty()) "все типы на месте" else bad.joinToString(" | "))
        return emptyList()
    }
}
