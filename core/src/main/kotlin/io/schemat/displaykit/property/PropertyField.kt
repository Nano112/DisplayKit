package io.schemat.displaykit.property

enum class PropertyChangeResult { CHANGED, AT_START, AT_END }

/** Renderer-neutral editable property. */
interface PropertyField {
    val id: String
    val label: String
    val description: String?
    val valueText: String
    val previousLabel: String?
    val nextLabel: String
    val canPrevious: Boolean
    val canNext: Boolean

    fun previous(): PropertyChangeResult
    fun next(): PropertyChangeResult
}

class IntPropertyField(
    override val id: String,
    override val label: String,
    val min: Int,
    val max: Int,
    val step: Int = 1,
    val unit: String? = null,
    override val description: String? = null,
    private val get: () -> Int,
    private val set: (Int) -> Unit
) : PropertyField {
    init {
        validateIdentity(id, label)
        require(min <= max) { "Property '$id' minimum must not exceed its maximum." }
        require(step > 0) { "Property '$id' step must be positive." }
    }

    val value: Int get() = get().coerceIn(min, max)
    override val valueText: String get() = value.toString() + unitSuffix(unit)
    override val previousLabel: String get() = "Decrease $label"
    override val nextLabel: String get() = "Increase $label"
    override val canPrevious: Boolean get() = value > min
    override val canNext: Boolean get() = value < max

    override fun previous(): PropertyChangeResult = change(-step, PropertyChangeResult.AT_START)
    override fun next(): PropertyChangeResult = change(step, PropertyChangeResult.AT_END)

    private fun change(delta: Int, boundary: PropertyChangeResult): PropertyChangeResult {
        val current = value
        val target = (current.toLong() + delta).coerceIn(min.toLong(), max.toLong()).toInt()
        if (target == current) return boundary
        set(target)
        return PropertyChangeResult.CHANGED
    }
}

class DoublePropertyField(
    override val id: String,
    override val label: String,
    val min: Double,
    val max: Double,
    val step: Double,
    val decimals: Int = 1,
    val unit: String? = null,
    override val description: String? = null,
    private val get: () -> Double,
    private val set: (Double) -> Unit
) : PropertyField {
    init {
        validateIdentity(id, label)
        require(min.isFinite() && max.isFinite() && step.isFinite()) { "Property '$id' bounds must be finite." }
        require(min <= max) { "Property '$id' minimum must not exceed its maximum." }
        require(step > 0.0) { "Property '$id' step must be positive." }
        require(decimals in 0..8) { "Property '$id' decimals must be between 0 and 8." }
    }

    val value: Double get() = get().coerceIn(min, max)
    override val valueText: String
        get() = "%.${decimals}f".format(java.util.Locale.ROOT, value) + unitSuffix(unit)
    override val previousLabel: String get() = "Decrease $label"
    override val nextLabel: String get() = "Increase $label"
    override val canPrevious: Boolean get() = value > min + 1e-12
    override val canNext: Boolean get() = value < max - 1e-12

    override fun previous(): PropertyChangeResult = change(-step, PropertyChangeResult.AT_START)
    override fun next(): PropertyChangeResult = change(step, PropertyChangeResult.AT_END)

    private fun change(delta: Double, boundary: PropertyChangeResult): PropertyChangeResult {
        val current = value
        val target = (current + delta).coerceIn(min, max)
        if (kotlin.math.abs(target - current) < 1e-12) return boundary
        set(target)
        return PropertyChangeResult.CHANGED
    }
}

class BooleanPropertyField(
    override val id: String,
    override val label: String,
    val trueLabel: String = "On",
    val falseLabel: String = "Off",
    override val description: String? = null,
    private val get: () -> Boolean,
    private val set: (Boolean) -> Unit
) : PropertyField {
    init { validateIdentity(id, label) }
    override val valueText: String get() = if (get()) trueLabel else falseLabel
    override val previousLabel: String? get() = null
    override val nextLabel: String get() = "Toggle $label"
    override val canPrevious: Boolean get() = false
    override val canNext: Boolean get() = true
    override fun previous(): PropertyChangeResult = PropertyChangeResult.AT_START
    override fun next(): PropertyChangeResult {
        set(!get())
        return PropertyChangeResult.CHANGED
    }
}

class ChoicePropertyField<T>(
    override val id: String,
    override val label: String,
    choices: List<T>,
    override val description: String? = null,
    private val format: (T) -> String = { it.toString() },
    private val get: () -> T,
    private val set: (T) -> Unit
) : PropertyField {
    val choices: List<T> = choices.toList()

    init {
        validateIdentity(id, label)
        require(this.choices.isNotEmpty()) { "Choice property '$id' needs at least one choice." }
        require(this.choices.distinct().size == this.choices.size) { "Choice property '$id' choices must be unique." }
    }

    private val index: Int get() = choices.indexOf(get()).also {
        check(it >= 0) { "Choice property '$id' value is not one of its configured choices." }
    }
    override val valueText: String get() = format(choices[index])
    override val previousLabel: String get() = "Previous $label"
    override val nextLabel: String get() = "Next $label"
    override val canPrevious: Boolean get() = index > 0
    override val canNext: Boolean get() = index + 1 < choices.size

    override fun previous(): PropertyChangeResult = select(index - 1, PropertyChangeResult.AT_START)
    override fun next(): PropertyChangeResult = select(index + 1, PropertyChangeResult.AT_END)

    private fun select(target: Int, boundary: PropertyChangeResult): PropertyChangeResult {
        if (target !in choices.indices) return boundary
        set(choices[target])
        return PropertyChangeResult.CHANGED
    }
}

class PropertySheetModel(fields: List<PropertyField>) {
    val fields: List<PropertyField> = fields.toList()

    init {
        val duplicates = this.fields.groupingBy { it.id }.eachCount().filterValues { it > 1 }.keys
        require(duplicates.isEmpty()) {
            "Property ids must be unique (duplicates: ${duplicates.joinToString()})."
        }
    }

    fun field(id: String): PropertyField? = fields.firstOrNull { it.id == id }
}

private fun validateIdentity(id: String, label: String) {
    require(id.isNotBlank()) { "A property id cannot be blank." }
    require(label.isNotBlank()) { "Property '$id' label cannot be blank." }
}

private fun unitSuffix(unit: String?): String = unit?.let { " $it" } ?: ""
