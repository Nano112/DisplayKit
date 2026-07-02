package io.schemat.displaykit.ui

import io.schemat.displaykit.math.Mat4f
import io.schemat.displaykit.math.Vec3d
import io.schemat.displaykit.math.Vec3f
import io.schemat.displaykit.platform.PlatformProvider
import io.schemat.displaykit.platform.PlayerRef
import io.schemat.displaykit.platform.TaskHandle
import io.schemat.displaykit.render.*
import io.schemat.displaykit.ui.elements.*
import org.joml.Matrix4f
import org.joml.Vector4f
import java.util.UUID
import java.util.concurrent.CopyOnWriteArraySet
import kotlin.math.abs
import kotlin.math.sqrt

class FloatingUI(
    val platform: PlatformProvider,
    val owner: PlayerRef,
    center: Vec3d,
    facing: Vec3d = owner.lookDirection(),
    private val maxDistance: Double = 15.0,
    private val timeoutTicks: Int = 1200,
    var debugMode: Boolean = false,
    private val positionProvider: (() -> Vec3d)? = null,
    val billboard: Boolean = false,
    private val customUp: Vec3d? = null
) {
    private val elements = mutableListOf<UIElement>()
    private var ticksAlive = 0
    private var isDestroyed = false
    private var tickTask: TaskHandle? = null

    // UI bounds for click-through detection (set via setBounds)
    var boundsWidth: Double = 0.0
        private set
    var boundsHeight: Double = 0.0
        private set

    // Viewer management
    private val viewers = CopyOnWriteArraySet<UUID>()
    private val allEntities = mutableListOf<VirtualEntity>()

    // Mutable center for position-provider-based updates
    var center: Vec3d = center
        private set

    // UI coordinate system
    val right: Vec3d
    val up: Vec3d
    val forward: Vec3d

    init {
        val upVec = customUp ?: Vec3d.UP
        val horizontalFacing = if (customUp != null) {
            // With custom up, project facing onto plane perpendicular to up
            val dot = facing.dot(upVec)
            (facing - upVec * dot).normalize()
        } else {
            Vec3d(facing.x, 0.0, facing.z).normalize()
        }
        right = horizontalFacing.cross(upVec).normalize()
        up = upVec
        forward = horizontalFacing
    }

    var transformMatrix: Matrix4f = calculateTransformMatrix()
        private set

    var onDestroy: (() -> Unit)? = null

    init {
        // Owner is always a viewer
        viewers.add(owner.uuid)
        startTickTask()
    }

    // --- Viewer management ---

    fun addViewer(player: PlayerRef) {
        if (viewers.add(player.uuid)) {
            // Spawn all existing entities for the new viewer
            val uuids = listOf(player.uuid)
            allEntities.forEach { entity ->
                platform.packetSender.spawnEntity(entity, uuids)
                platform.packetSender.updateMetadata(entity, uuids)
            }
        }
    }

    fun removeViewer(player: PlayerRef) {
        if (viewers.remove(player.uuid)) {
            val entityIds = allEntities.map { it.entityId }
            if (entityIds.isNotEmpty()) {
                platform.packetSender.destroyEntities(entityIds, listOf(player.uuid))
            }
        }
    }

    fun addAllViewers() {
        platform.getOnlinePlayers().forEach { addViewer(it) }
    }

    fun getViewerUUIDs(): Collection<UUID> = viewers.toSet()

    // --- Entity management (called by UIElements) ---

    internal fun spawnEntity(entity: VirtualEntity) {
        if (billboard) {
            entity.billboard = io.schemat.displaykit.render.Billboard.VERTICAL
        }
        allEntities.add(entity)
        val uuids = viewers.toSet()
        platform.packetSender.spawnEntity(entity, uuids)
        platform.packetSender.updateMetadata(entity, uuids)
    }

    internal fun despawnEntity(entity: VirtualEntity) {
        allEntities.remove(entity)
        platform.packetSender.destroyEntities(listOf(entity.entityId), viewers.toSet())
    }

    internal fun despawnEntities(entities: List<VirtualEntity>) {
        allEntities.removeAll(entities.toSet())
        val ids = entities.map { it.entityId }
        if (ids.isNotEmpty()) {
            platform.packetSender.destroyEntities(ids, viewers.toSet())
        }
    }

    internal fun updateEntity(entity: VirtualEntity) {
        platform.packetSender.updateMetadata(entity, viewers.toSet())
    }

    internal fun teleportEntity(entity: VirtualEntity) {
        platform.packetSender.teleportEntity(entity, viewers.toSet())
    }

    // --- Tick loop ---

    private fun startTickTask() {
        tickTask = platform.scheduler.scheduleRepeating(1L, 1L, Runnable {
            if (isDestroyed) return@Runnable
            tick()
        })
    }

    private fun updateCenter() {
        val provider = positionProvider ?: return
        val newCenter = provider()
        val delta = newCenter - center
        if (delta.x == 0.0 && delta.y == 0.0 && delta.z == 0.0) return
        center = newCenter
        transformMatrix = calculateTransformMatrix()
        for (entity in allEntities) {
            entity.position = entity.position + delta
            platform.packetSender.teleportEntity(entity, viewers.toSet())
        }
    }

    private fun tick() {
        ticksAlive++

        updateCenter()

        val distance = owner.eyePosition().distance(center)
        if (distance > maxDistance || ticksAlive > timeoutTicks || !owner.isOnline()) {
            destroy()
            return
        }

        updateHoverStates()

        elements.filterIsInstance<SliderElement>().forEach { slider ->
            if (slider.isDragging()) {
                slider.update()
            }
        }
    }

    private fun updateHoverStates() {
        val eyePos = owner.eyePosition()
        val lookDir = owner.lookDirection()

        var closestElement: UIElement? = null
        var closestDistance = Double.MAX_VALUE

        for (element in elements) {
            if (!element.isInteractive) continue

            val elementPos = element.getWorldPosition()

            val result = if (element.usesRectangularHitbox()) {
                isLookingAtRectangularElement(
                    eyePos, lookDir, element.localOffset,
                    element.hitboxWidth, element.hitboxHeight
                )
            } else {
                isLookingAtElement(eyePos, lookDir, elementPos, element.hitboxSize)
            }

            if (result != null) {
                val (dist, _) = result
                if (dist < closestDistance) {
                    closestElement = element
                    closestDistance = dist
                }
            }
        }

        for (element in elements) {
            val wasHovered = element.isHovered
            element.isHovered = element == closestElement

            if (element.isHovered != wasHovered) {
                element.onHoverChanged()
            }
        }
    }

    // --- Coordinate system ---

    private fun calculateTransformMatrix(): Matrix4f {
        return Matrix4f(
            right.x.toFloat(), up.x.toFloat(), forward.x.toFloat(), center.x.toFloat(),
            right.y.toFloat(), up.y.toFloat(), forward.y.toFloat(), center.y.toFloat(),
            right.z.toFloat(), up.z.toFloat(), forward.z.toFloat(), center.z.toFloat(),
            0f, 0f, 0f, 1f
        ).transpose()
    }

    fun localToWorld(x: Double, y: Double, z: Double): Vec3d {
        val localPos = Vector4f(x.toFloat(), y.toFloat(), z.toFloat(), 1f)
        transformMatrix.transform(localPos)
        return Vec3d(localPos.x.toDouble(), localPos.y.toDouble(), localPos.z.toDouble())
    }

    fun buildUIElementMatrix(
        localOffsetX: Float,
        localOffsetY: Float,
        localOffsetZ: Float,
        scaleX: Float,
        scaleY: Float,
        scaleZ: Float = 0.01f
    ): Mat4f {
        val rotationMatrix = if (billboard) {
            Matrix4f() // identity - billboard handles rotation
        } else {
            Matrix4f(
                right.x.toFloat(), right.y.toFloat(), right.z.toFloat(), 0f,
                up.x.toFloat(), up.y.toFloat(), up.z.toFloat(), 0f,
                -forward.x.toFloat(), -forward.y.toFloat(), -forward.z.toFloat(), 0f,
                0f, 0f, 0f, 1f
            )
        }

        val scaleMatrix = Matrix4f().scaling(scaleX, scaleY, scaleZ)
        val offsetMatrix = Matrix4f().translation(localOffsetX, localOffsetY, localOffsetZ)

        return Mat4f(Matrix4f(rotationMatrix).mul(offsetMatrix).mul(scaleMatrix))
    }

    fun buildPanelMatrix(width: Float, height: Float, depth: Float = 0.02f): Mat4f {
        val matrix = Matrix4f()
        matrix.translation(-0.5f, -0.5f, -0.5f)

        val basis = if (billboard) {
            Matrix4f() // identity - billboard handles rotation
        } else {
            Matrix4f(
                right.x.toFloat(), right.y.toFloat(), right.z.toFloat(), 0f,
                up.x.toFloat(), up.y.toFloat(), up.z.toFloat(), 0f,
                forward.x.toFloat(), forward.y.toFloat(), forward.z.toFloat(), 0f,
                0f, 0f, 0f, 1f
            )
        }

        val scale = Matrix4f().scaling(width, height, depth)
        return Mat4f(Matrix4f(basis).mul(scale).mul(matrix))
    }

    fun getPlaneNormal(): Vec3d {
        return forward * -1.0
    }

    fun calculatePosition(offsetRight: Double, offsetUp: Double, offsetForward: Double): Vec3d {
        val safeRight = if (offsetRight.isFinite()) offsetRight else 0.0
        val safeUp = if (offsetUp.isFinite()) offsetUp else 0.0
        val safeForward = if (offsetForward.isFinite()) offsetForward else 0.0

        val worldPos = localToWorld(safeRight, safeUp, safeForward)

        val maxDist = 1000.0
        if (worldPos.x.isNaN() || worldPos.y.isNaN() || worldPos.z.isNaN() ||
            abs(worldPos.x - center.x) > maxDist ||
            abs(worldPos.y - center.y) > maxDist ||
            abs(worldPos.z - center.z) > maxDist
        ) {
            return center
        }

        return worldPos
    }

    fun getMousePositionOnPlane(): Pair<Double, Double>? {
        val eye = owner.eyePosition()
        val direction = owner.lookDirection()
        val planeNormal = getPlaneNormal()

        val planeDistance = rayIntersectsPlane(eye, direction, center, planeNormal) ?: return null
        val hitPoint = eye + direction * planeDistance

        val relativePoint = hitPoint - center
        val localX = relativePoint.dot(right)
        val localY = relativePoint.dot(up)

        return Pair(localX, localY)
    }

    // --- Hit detection ---

    private fun rayIntersectsPlane(
        rayOrigin: Vec3d, rayDirection: Vec3d,
        planePoint: Vec3d, planeNormal: Vec3d,
        maxDist: Double = 15.0
    ): Double? {
        val denominator = planeNormal.dot(rayDirection)
        if (abs(denominator) < 0.0001) return null
        val t = planeNormal.dot(planePoint - rayOrigin) / denominator
        if (t < 0 || t > maxDist) return null
        return t
    }

    private fun isLookingAtElement(
        eye: Vec3d, direction: Vec3d, elementCenter: Vec3d, hitboxSize: Double
    ): Pair<Double, Double>? {
        val planeNormal = getPlaneNormal()
        val planeDistance = rayIntersectsPlane(eye, direction, center, planeNormal) ?: return null
        val hitPoint = eye + direction * planeDistance
        val offset = hitPoint.distance(elementCenter)
        return if (offset < hitboxSize) Pair(planeDistance, offset) else null
    }

    private fun isLookingAtRectangularElement(
        eye: Vec3d, direction: Vec3d,
        elementLocalOffset: Vec3d,
        hitboxWidth: Double, hitboxHeight: Double
    ): Pair<Double, Double>? {
        val planeNormal = getPlaneNormal()
        val planeDistance = rayIntersectsPlane(eye, direction, center, planeNormal) ?: return null
        val hitPoint = eye + direction * planeDistance
        val relativePoint = hitPoint - center
        val localX = relativePoint.dot(right)
        val localY = relativePoint.dot(up)

        val halfWidth = hitboxWidth / 2
        val halfHeight = hitboxHeight / 2
        val minX = elementLocalOffset.x - halfWidth
        val maxX = elementLocalOffset.x + halfWidth
        val minY = elementLocalOffset.y - halfHeight
        val maxY = elementLocalOffset.y + halfHeight

        if (localX in minX..maxX && localY in minY..maxY) {
            val offsetX = localX - elementLocalOffset.x
            val offsetY = localY - elementLocalOffset.y
            val offset = sqrt(offsetX * offsetX + offsetY * offsetY)
            return Pair(planeDistance, offset)
        }
        return null
    }

    // --- Bounds ---

    fun setBounds(width: Double, height: Double) {
        boundsWidth = width
        boundsHeight = height
    }

    /**
     * Check if the owner's look ray hits within the UI's bounding rectangle.
     * Returns true if the player is looking at the UI panel area.
     */
    fun isPlayerLookingAt(): Boolean {
        if (isDestroyed || boundsWidth <= 0 || boundsHeight <= 0) return false
        val mouse = getMousePositionOnPlane() ?: return false
        val (localX, localY) = mouse
        return abs(localX) <= boundsWidth / 2 && abs(localY) <= boundsHeight / 2
    }

    /** Returns the ray distance to this UI's plane if the player is looking within bounds, or null. */
    fun hitDistance(): Double? {
        if (isDestroyed || boundsWidth <= 0 || boundsHeight <= 0) return null
        val eye = owner.eyePosition()
        val dir = owner.lookDirection()
        val t = rayIntersectsPlane(eye, dir, center, getPlaneNormal()) ?: return null
        val hitPoint = eye + dir * t
        val rel = hitPoint - center
        val localX = rel.dot(right)
        val localY = rel.dot(up)
        return if (abs(localX) <= boundsWidth / 2 && abs(localY) <= boundsHeight / 2) t else null
    }

    // --- Click handling ---

    fun handleClick(isRightClick: Boolean) {
        if (isDestroyed) return
        updateHoverStates()

        val hoveredElement = elements.find { it.isHovered && it.isInteractive }
        if (hoveredElement != null && hoveredElement.canInteract()) {
            hoveredElement.markInteracted()
            hoveredElement.onClick?.invoke()
        }
    }

    // --- Element factory methods ---

    fun addButton(
        offsetRight: Double, offsetUp: Double, offsetForward: Double = 0.0,
        label: String? = null,
        material: BlockStateRef = BlockStateRef.STONE,
        hoverMaterial: BlockStateRef = BlockStateRef.GOLD_BLOCK,
        size: Float = 0.3f,
        onClick: () -> Unit
    ): ButtonElement {
        val element = ButtonElement(
            ui = this, localOffset = Vec3d(offsetRight, offsetUp, offsetForward),
            label = label, material = material, hoverMaterial = hoverMaterial,
            size = size, onClick = onClick
        )
        element.spawn()
        elements.add(element)
        return element
    }

    fun addLabel(
        offsetRight: Double, offsetUp: Double, offsetForward: Double = 0.0,
        text: String, scale: Float = 1.0f,
        backgroundColor: DkColor = DkColor.TRANSPARENT
    ): LabelElement {
        val element = LabelElement(
            ui = this, localOffset = Vec3d(offsetRight, offsetUp, offsetForward),
            text = text, scale = scale, backgroundColor = backgroundColor
        )
        element.spawn()
        elements.add(element)
        return element
    }

    fun addAlignedLabel(
        offsetRight: Double, offsetUp: Double, offsetForward: Double = 0.0,
        text: String, scale: Float = 0.4f,
        backgroundColor: DkColor = DkColor.TRANSPARENT
    ): AlignedLabelElement {
        val element = AlignedLabelElement(
            ui = this, localOffset = Vec3d(offsetRight, offsetUp, offsetForward),
            text = text, scale = scale, backgroundColor = backgroundColor
        )
        element.spawn()
        elements.add(element)
        return element
    }

    fun addPanel(
        offsetRight: Double, offsetUp: Double, offsetForward: Double = 0.1,
        width: Float, height: Float,
        material: BlockStateRef = BlockStateRef.BLACK_CONCRETE,
        rotateToFace: Boolean = true
    ): PanelElement {
        val element = PanelElement(
            ui = this, localOffset = Vec3d(offsetRight, offsetUp, offsetForward),
            width = width, height = height, material = material, rotateToFace = rotateToFace
        )
        element.spawn()
        elements.add(element)
        return element
    }

    /**
     * Add a styled panel that supports shader effects like rounded corners and glassmorphism.
     * Unlike addPanel (which uses block displays), styled panels use text_display backgrounds
     * that can be processed by our custom resource pack shaders.
     *
     * @param backgroundColor Color with optional encoded effects via withCornerRadius() or withGlass()
     */
    fun addStyledPanel(
        offsetRight: Double, offsetUp: Double, offsetForward: Double = 0.1,
        width: Float, height: Float,
        backgroundColor: DkColor
    ): StyledPanelElement {
        val element = StyledPanelElement(
            ui = this, localOffset = Vec3d(offsetRight, offsetUp, offsetForward),
            width = width, height = height, backgroundColor = backgroundColor
        )
        element.spawn()
        elements.add(element)
        return element
    }

    fun addInteractivePanel(
        offsetRight: Double, offsetUp: Double, offsetForward: Double = 0.0,
        width: Float, height: Float,
        material: BlockStateRef = BlockStateRef.BLACK_CONCRETE,
        hoverMaterial: BlockStateRef? = BlockStateRef.GRAY_CONCRETE,
        onClick: () -> Unit
    ): InteractivePanelElement {
        val element = InteractivePanelElement(
            ui = this, localOffset = Vec3d(offsetRight, offsetUp, offsetForward),
            width = width, height = height, material = material,
            hoverMaterial = hoverMaterial, onClick = onClick
        )
        element.spawn()
        elements.add(element)
        return element
    }

    fun addGrabber(
        offsetRight: Double, offsetUp: Double, offsetForward: Double = 0.0,
        material: BlockStateRef = BlockStateRef.LIGHT_BLUE_CONCRETE,
        hoverMaterial: BlockStateRef = BlockStateRef.YELLOW_CONCRETE,
        size: Float = 0.15f, visible: Boolean = true,
        onClick: () -> Unit
    ): GrabberElement {
        val element = GrabberElement(
            ui = this, localOffset = Vec3d(offsetRight, offsetUp, offsetForward),
            material = material, hoverMaterial = hoverMaterial,
            size = size, initiallyVisible = visible, onClick = onClick
        )
        element.spawn()
        elements.add(element)
        return element
    }

    fun addTextInput(
        offsetRight: Double, offsetUp: Double, offsetForward: Double = 0.0,
        width: Float = 2.0f, height: Float = 0.3f,
        placeholder: String = "Click to edit...", value: String = "",
        maxLength: Int = 50, onValueChange: (String) -> Unit = {}
    ): TextInputElement {
        val element = TextInputElement(
            ui = this, localOffset = Vec3d(offsetRight, offsetUp, offsetForward),
            width = width, height = height, placeholder = placeholder,
            value = value, maxLength = maxLength, onValueChange = onValueChange
        )
        element.spawn()
        elements.add(element)
        return element
    }

    fun addSlider(
        offsetRight: Double, offsetUp: Double, offsetForward: Double = 0.0,
        width: Float = 2.0f, minValue: Float = 0f, maxValue: Float = 100f,
        value: Float = 50f, step: Float = 1f, showValue: Boolean = true,
        label: String? = null, trackHeight: Float = 0.1f,
        handleWidth: Float = 0.15f, handleHeight: Float = 0.15f,
        onValueChange: (Float) -> Unit = {}
    ): SliderElement {
        val element = SliderElement(
            ui = this, localOffset = Vec3d(offsetRight, offsetUp, offsetForward),
            width = width, minValue = minValue, maxValue = maxValue,
            value = value, step = step, showValue = showValue,
            label = label, trackHeight = trackHeight,
            handleWidth = handleWidth, handleHeight = handleHeight,
            onValueChange = onValueChange
        )
        element.spawn()
        elements.add(element)
        return element
    }

    fun addCheckbox(
        offsetRight: Double, offsetUp: Double, offsetForward: Double = 0.0,
        checked: Boolean = false, label: String? = null,
        onToggle: (Boolean) -> Unit = {}
    ): CheckboxElement {
        val element = CheckboxElement(
            ui = this, localOffset = Vec3d(offsetRight, offsetUp, offsetForward),
            checked = checked, label = label, onToggle = onToggle
        )
        element.spawn()
        elements.add(element)
        return element
    }

    fun addProgressBar(
        offsetRight: Double, offsetUp: Double, offsetForward: Double = 0.0,
        width: Float = 2.0f, height: Float = 0.15f, progress: Float = 0f,
        showPercentage: Boolean = true, label: String? = null,
        barColor: BlockStateRef = BlockStateRef.LIME_CONCRETE,
        backgroundColor: BlockStateRef = BlockStateRef.GRAY_CONCRETE
    ): ProgressBarElement {
        val element = ProgressBarElement(
            ui = this, localOffset = Vec3d(offsetRight, offsetUp, offsetForward),
            width = width, height = height, progress = progress,
            showPercentage = showPercentage, label = label,
            barColor = barColor, backgroundColor = backgroundColor
        )
        element.spawn()
        elements.add(element)
        return element
    }

    fun addTabs(
        offsetRight: Double, offsetUp: Double, offsetForward: Double = 0.0,
        tabs: List<TabDefinition>, tabWidth: Float = 0.8f, tabHeight: Float = 0.3f,
        gap: Float = 0.05f, selectedIndex: Int = 0,
        onTabChange: (Int, TabDefinition) -> Unit = { _, _ -> }
    ): TabsElement {
        val element = TabsElement(
            ui = this, localOffset = Vec3d(offsetRight, offsetUp, offsetForward),
            tabs = tabs, tabWidth = tabWidth, tabHeight = tabHeight,
            gap = gap, selectedIndex = selectedIndex, onTabChange = onTabChange
        )
        element.spawn()
        elements.add(element)

        element.getTabButtons().forEach { tabButton ->
            elements.add(tabButton)
        }

        return element
    }

    // --- Lifecycle ---

    fun removeElement(element: UIElement) {
        element.destroy()
        elements.remove(element)
    }

    fun getElements(): List<UIElement> = elements.toList()

    fun destroy() {
        if (isDestroyed) return
        isDestroyed = true

        tickTask?.cancel()
        elements.forEach { it.destroy() }
        elements.clear()

        // Destroy all remaining entities for all viewers
        if (allEntities.isNotEmpty()) {
            val ids = allEntities.map { it.entityId }
            platform.packetSender.destroyEntities(ids, viewers.toSet())
            allEntities.clear()
        }

        onDestroy?.invoke()
        InteractionRouter.unregisterUI(owner.uuid, this)
    }

    fun isDestroyed() = isDestroyed

    companion object {
        fun create(
            platform: PlatformProvider,
            owner: PlayerRef,
            center: Vec3d,
            facing: Vec3d = owner.lookDirection(),
            debugMode: Boolean = false,
            positionProvider: (() -> Vec3d)? = null
        ): FloatingUI {
            val ui = FloatingUI(platform, owner, center, facing, debugMode = debugMode, positionProvider = positionProvider)
            InteractionRouter.registerUI(owner.uuid, ui)
            return ui
        }
    }
}
