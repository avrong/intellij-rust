/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.ide.intentions

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.search.searches.ReferencesSearch
import org.rust.ide.refactoring.RsInPlaceVariableIntroducer
import org.rust.lang.core.ARBITRARY_ENUM_DISCRIMINANT
import org.rust.lang.core.FeatureAvailability
import org.rust.lang.core.psi.*
import org.rust.lang.core.psi.ext.ancestorStrict
import org.rust.lang.core.psi.ext.bounds
import org.rust.lang.core.psi.ext.isFieldless
import org.rust.lang.core.psi.ext.parentEnum
import org.rust.lang.core.types.consts.Const
import org.rust.lang.core.types.consts.CtConstParameter
import org.rust.lang.core.types.infer.TypeVisitor
import org.rust.lang.core.types.infer.hasCtConstParameters
import org.rust.lang.core.types.infer.hasTyTypeParameters
import org.rust.lang.core.types.ty.Ty
import org.rust.lang.core.types.ty.TyTypeParameter
import org.rust.lang.core.types.type

class ExtractEnumVariantIntention : RsElementBaseIntentionAction<ExtractEnumVariantIntention.Context>() {
    override fun getText(): String = "Extract enum variant"
    override fun getFamilyName(): String = text

    class Context(val variant: RsEnumVariant)

    override fun findApplicableContext(project: Project, editor: Editor, element: PsiElement): Context? {
        val variant = element.ancestorStrict<RsEnumVariant>() ?: return null

        if (variant.isFieldless) {
            return null
        }

        if (variant.variantDiscriminant != null &&
            ARBITRARY_ENUM_DISCRIMINANT.availability(variant.containingMod) != FeatureAvailability.AVAILABLE) {
            return null
        }

        return Context(variant)
    }

    override fun invoke(project: Project, editor: Editor, ctx: Context) {
        val element = createElement(ctx.variant)
        val factory = RsPsiFactory(project)

        val enum = ctx.variant.parentEnum
        val parameters = filterTypeParameters(element.typeReferences, enum.typeParameterList)
        val typeParametersText = parameters.format()
        val whereClause = buildWhereClause(enum.whereClause, parameters)
        val name = ctx.variant.name ?: return

        val struct = element.createStruct(enum.vis?.text, name, typeParametersText, whereClause)
        val inserted = enum.parent.addBefore(struct, enum) as RsStructItem

        for (usage in ReferencesSearch.search(ctx.variant)) {
            element.replaceUsage(usage.element, name)
        }

        val tupleField = RsPsiFactory.TupleField(
            inserted.declaredType,
            addPub = false // enum variant's fields are pub by default
        )
        val newFields = factory.createTupleFields(listOf(tupleField))
        val replaced = element.toBeReplaced.replace(newFields)

        offerStructRename(project, editor, inserted, replaced)
    }

    companion object {

        private data class Parameters(
            val lifetimes: List<RsLifetimeParameter> = emptyList(),
            val typeParameters: List<RsTypeParameter> = emptyList(),
            val constParameters: List<RsConstParameter> = emptyList()
        ) {
            fun format(): String {
                val all = lifetimes + typeParameters + constParameters
                return if (all.isNotEmpty()) {
                    all.joinToString(", ", prefix = "<", postfix = ">") { it.text }
                } else {
                    ""
                }
            }
        }

        private fun filterTypeParameters(
            references: List<RsTypeReference>,
            parameters: RsTypeParameterList?
        ): Parameters {
            if (parameters == null) return Parameters()
            val typeParameters = gatherTypeParameters(references, parameters.typeParameterList)
            val lifetimes = gatherLifetimes(references, parameters.lifetimeParameterList, typeParameters)
            val constParameters = parameters.constParameterList
                .filter { param -> references.any { matchesConstParameter(it, param) } }
            return Parameters(lifetimes, typeParameters, constParameters)
        }

        private fun matchesConstParameter(ref: RsTypeReference, parameter: RsConstParameter): Boolean =
            ref.type.visitWith(HasConstParameterVisitor(parameter))

        private fun offerStructRename(project: Project, editor: Editor, inserted: RsStructItem, replaced: PsiElement) {
            val range = inserted.identifier?.textRange ?: return
            editor.caretModel.moveToOffset(range.startOffset)

            PsiDocumentManager.getInstance(project).doPostponedOperationsAndUnblockDocument(editor.document)
            RsInPlaceVariableIntroducer(inserted, editor, project, "choose struct name")
                .performInplaceRefactoring(null)
        }

        private fun createElement(variant: RsEnumVariant): VariantElement {
            val tupleFields = variant.tupleFields
            val blockFields = variant.blockFields

            return when {
                tupleFields != null -> TupleVariant(tupleFields)
                blockFields != null -> StructVariant(blockFields)
                else -> error("unreachable")
            }
        }

        private fun buildWhereClause(whereClause: RsWhereClause?, parameters: Parameters): String {
            val where = whereClause ?: return ""
            if (where.wherePredList.isEmpty()) return ""

            val parameterMap = parameters.typeParameters.filter { it.name != null }.associateBy { it.name!! }
            val lifetimeMap = parameters.lifetimes.filter { it.name != null }.associateBy { it.name!! }
            val predicates = where.wherePredList.mapNotNull { predicate ->
                val typeRef = predicate.typeReference
                if (typeRef != null && hasTypeParameter(typeRef, parameterMap)) {
                    return@mapNotNull predicate.text
                }

                val lifetime = predicate.lifetime
                if (lifetime != null) {
                    return@mapNotNull createLifetimePredicate(
                        predicate,
                        lifetime,
                        predicate.lifetimeParamBounds,
                        lifetimeMap
                    )
                }

                null
            }

            return if (predicates.isNotEmpty()) {
                predicates.joinToString(separator = ",", prefix = " where ")
            } else {
                ""
            }
        }

        /**
         * Create a predicate if the lifetime is in the map and at least one of its bounds is in the map.
         * Bounds that are not in the map are removed.
         */
        private fun createLifetimePredicate(
            predicate: RsWherePred,
            lifetime: RsLifetime,
            lifetimeParamBounds: RsLifetimeParamBounds?,
            lifetimeMap: Map<String, RsLifetimeParameter>
        ): String? {
            if (lifetime.name !in lifetimeMap) return null
            if (lifetimeParamBounds == null) return predicate.text
            val bounds = lifetimeParamBounds.lifetimeList.filter { it.name in lifetimeMap }
            return if (bounds.isNotEmpty()) {
                "${lifetime.text}: ${bounds.joinToString(" + ") { it.text }}"
            } else {
                null
            }
        }

        private fun hasTypeParameter(ref: RsTypeReference, map: Map<String, RsTypeParameter>): Boolean =
            HasTypeParameterVisitor(map, ref).visitTy(ref.type)
    }
}

private sealed class VariantElement(val toBeReplaced: PsiElement) {
    abstract val typeReferences: List<RsTypeReference>
    protected val factory: RsPsiFactory get() = RsPsiFactory(toBeReplaced.project)

    abstract fun createStruct(vis: String?, name: String, typeParameters: String, whereClause: String): RsStructItem

    abstract fun replaceUsage(element: PsiElement, name: String)
}

private class TupleVariant(val fields: RsTupleFields) : VariantElement(fields) {
    override val typeReferences: List<RsTypeReference> = fields.tupleFieldDeclList.map { it.typeReference }

    override fun createStruct(vis: String?, name: String, typeParameters: String, whereClause: String): RsStructItem {
        val formattedVis = if (vis == null) "" else "$vis "
        return factory.createStruct("${formattedVis}struct $name$typeParameters${fields.text}$whereClause;")
    }

    override fun replaceUsage(element: PsiElement, name: String) {
        if (replaceTuplePattern(element, name)) return
        replaceTupleCall(element, name)
    }

    private fun replaceTupleCall(element: PsiElement, name: String): Boolean {
        val parent = element.ancestorStrict<RsCallExpr>() ?: return false
        val call = factory.createFunctionCall(name, parent.valueArgumentList.text.trim('(', ')'))
        val binding = factory.createFunctionCall(element.text, listOf(call))
        parent.replace(binding)
        return true
    }

    private fun replaceTuplePattern(element: PsiElement, name: String): Boolean {
        val parent = element.ancestorStrict<RsPatTupleStruct>() ?: return false
        val binding = factory.createPatTupleStruct(name, parent.patList)

        for (pat in parent.patList) {
            val comma = pat.nextSibling
            pat.delete()
            if (comma.text == ",") {
                comma.delete()
            }
        }

        parent.addAfter(binding, parent.lparen)
        return true
    }
}

private class StructVariant(val fields: RsBlockFields) : VariantElement(fields) {
    override val typeReferences: List<RsTypeReference> = fields.namedFieldDeclList.mapNotNull { it.typeReference }

    override fun createStruct(vis: String?, name: String, typeParameters: String, whereClause: String): RsStructItem {
        val formattedVis = if (vis == null) "" else "$vis "
        return factory.createStruct("${formattedVis}struct $name$typeParameters$whereClause${fields.text}")
    }

    override fun replaceUsage(element: PsiElement, name: String) {
        if (replaceStructPattern(element, name)) return
        replaceStructLiteral(element, name)
    }

    private fun replaceStructPattern(element: PsiElement, name: String): Boolean {
        val parent = element.ancestorStrict<RsPatStruct>() ?: return false
        val path = parent.path
        val binding = factory.createPatStruct(name, parent.patFieldList, parent.patRest)
        val newPat = factory.createPatTupleStruct(path.text, listOf(binding))
        parent.replace(newPat)
        return true
    }

    private fun replaceStructLiteral(element: PsiElement, name: String): Boolean {
        val parent = element.ancestorStrict<RsStructLiteral>() ?: return false
        val literal = factory.createStructLiteral(name, parent.structLiteralBody.text)
        val binding = factory.createFunctionCall(element.text, listOf(literal))
        parent.replace(binding)
        return true
    }
}

private data class CollectTypeParametersVisitor(
    val parameters: Map<String, RsTypeParameter>,
    val collected: MutableSet<RsTypeParameter>
) : RsRecursiveVisitor() {
    override fun visitTypeReference(ref: RsTypeReference) {
        super.visitTypeReference(ref)
        val type = ref.type as? TyTypeParameter ?: return
        val parameter = parameters[type.name] ?: return
        collected.add(parameter)
        parameter.bounds.forEach { bound ->
            bound.accept(this)
        }
    }
}

fun gatherTypeParameters(references: List<RsTypeReference>, parameters: List<RsTypeParameter>): List<RsTypeParameter> {
    val parameterMap = parameters.filter { it.name != null }.associateBy { it.name!! }
    val collected = mutableSetOf<RsTypeParameter>()
    for (ref in references) {
        ref.accept(CollectTypeParametersVisitor(parameterMap, collected))
    }
    return collected.sortedBy { parameters.indexOf(it) }
}

private data class CollectLifetimesVisitor(
    val parameters: Map<String, RsTypeParameter>,
    val lifetimeMap: Map<String, RsLifetimeParameter>,
    val collected: MutableSet<RsLifetimeParameter>
) : RsRecursiveVisitor() {

    override fun visitTypeReference(ref: RsTypeReference) {
        super.visitTypeReference(ref)
        val type = ref.type as? TyTypeParameter ?: return
        val parameter = parameters[type.name] ?: return
        parameter.bounds.forEach { bound ->
            bound.accept(this)
        }
    }

    override fun visitLifetime(lifetime: RsLifetime) {
        super.visitLifetime(lifetime)
        val parameter = lifetimeMap[lifetime.name] ?: return
        if (parameter !in collected) {
            collected.add(parameter)
            parameter.accept(this)
        }
    }
}

fun gatherLifetimes(
    references: List<RsTypeReference>,
    lifetimes: List<RsLifetimeParameter>,
    parameters: List<RsTypeParameter>
): List<RsLifetimeParameter> {
    val parameterMap = parameters.filter { it.name != null }.associateBy { it.name!! }
    val lifetimeMap = lifetimes.filter { it.name != null }.associateBy { it.name!! }
    val collected = mutableSetOf<RsLifetimeParameter>()

    for (ref in references) {
        ref.accept(CollectLifetimesVisitor(parameterMap, lifetimeMap, collected))
    }

    return collected.sortedBy { lifetimes.indexOf(it) }
}


private data class HasConstParameterVisitor(val parameter: RsConstParameter) : TypeVisitor {

    override fun visitTy(ty: Ty): Boolean =
        if (ty.hasCtConstParameters) ty.superVisitWith(this) else false

    override fun visitConst(const: Const): Boolean =
        when {
            const is CtConstParameter -> const.parameter == parameter
            const.hasCtConstParameters -> const.superVisitWith(this)
            else -> false
        }
}

private data class HasTypeParameterVisitor(
    val parameters: Map<String, RsTypeParameter>,
    val ref: RsTypeReference
) : TypeVisitor {
    override fun visitTy(ty: Ty): Boolean =
        when {
            ty is TyTypeParameter -> ty.name in parameters
            ty.hasTyTypeParameters -> ty.superVisitWith(this)
            else -> false
        }
}
