package io.schemat.displaykit.ui

import io.schemat.displaykit.math.Mat4f
import io.schemat.displaykit.math.Vec3d
import io.schemat.displaykit.math.Vec3f
import io.schemat.displaykit.platform.PlatformProvider
import io.schemat.displaykit.platform.PlayerRef
import io.schemat.displaykit.platform.TaskHandle
import io.schemat.displaykit.render.*
import io.schemat.displaykit.sprite.SpriteCanvas
import io.schemat.displaykit.ui.elements.*
import org.joml.Matrix4f
import org.joml.Vector4f
import java.util.UUID
import java.util.concurrent.CopyOnWriteArraySet
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class FloatingUI @JvmOverloads constructor(
    val platform: PlatformProvider,
    val owner: PlayerRef,
    center: Vec3d,
    facing: Vec3d = owner.lookDirection(),
    private val maxDistance: Double = 15.0,
    private val timeoutTicks: Int = 1200,
    var debugMode: Boolean = false,
    private val positionProvider: (() -> Vec3d)? = null,
    val billboard: Boolean = false,
    private val customUp: Vec3d? = null,
    /**
     * Pinned-but-rotating mode: the UI stays at [center] but its whole basis
     * yaws (around [up]) to face the nearest player, all elements rotating
     * coherently as one rigid panel — unlike [billboard], which rotates each
     * display entity around its own origin and fans multi-element layouts apart.
     */
    private val autoFace: Boolean = false,
    /** Players farther than this from [center] don't attract the panel. */
    private val autoFaceRange: Double = 12.0
) {
    private val elements = mutableListOf<UIElement>()
    private var ticksAlive = 0
    private var isDestroyed = false
    private var tickTask: TaskHandle? = null

    /**
     * Optional per-tick callback for consumers that poll live data (e.g. I/O
     * readouts). Invoked at the end of the UI's own tick, after hover updates;
     * do rate limiting on the consumer side.
     */
    var onTick: (() -> Unit)? = null

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

    // UI coordinate system. With autoFace the basis yaws around `up` at
    // runtime; `baseRight`/`baseForward` hold the yaw-zero orientation.
    var right: Vec3d
        private set
    val up: Vec3d
    var forward: Vec3d
        private set
    private val baseRight: Vec3d
    private val baseForward: Vec3d

    // Auto-face state: rotation (radians) around `up` relative to the base basis
    private var currentYaw = 0.0
    private val rotationSnapshots = HashMap<Int, RotationSnapshot>()

    private class RotationSnapshot(
        val entity: VirtualEntity,
        /** Position relative to center, expressed at yaw 0. */
        var relPos: Vec3d,
        /** Entity transformation, expressed at yaw 0. */
        var transformation: Matrix4f
    )

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
        baseRight = right
        baseForward = forward
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
        if (autoFace) captureRotationSnapshot(entity)
        val uuids = viewers.toSet()
        platform.packetSender.spawnEntity(entity, uuids)
        platform.packetSender.updateMetadata(entity, uuids)
    }

    internal fun despawnEntity(entity: VirtualEntity) {
        allEntities.remove(entity)
        rotationSnapshots.remove(entity.entityId)
        platform.packetSender.destroyEntities(listOf(entity.entityId), viewers.toSet())
    }

    internal fun despawnEntities(entities: List<VirtualEntity>) {
        allEntities.removeAll(entities.toSet())
        entities.forEach { rotationSnapshots.remove(it.entityId) }
        val ids = entities.map { it.entityId }
        if (ids.isNotEmpty()) {
            platform.packetSender.destroyEntities(ids, viewers.toSet())
        }
    }

    internal fun updateEntity(entity: VirtualEntity) {
        // Element-driven pose change (slider drag, tab highlight, ...): the
        // element computed it in the CURRENT rotated basis, so re-normalize
        // its yaw-zero snapshot before the next auto-face update.
        if (autoFace) captureRotationSnapshot(entity)
        platform.packetSender.updateMetadata(entity, viewers.toSet())
    }

    internal fun teleportEntity(entity: VirtualEntity) {
        if (autoFace) captureRotationSnapshot(entity)
        platform.packetSender.teleportEntity(entity, viewers.toSet())
    }

    /** Records the entity's pose normalized back to yaw 0. */
    private fun captureRotationSnapshot(entity: VirtualEntity) {
        val relPos = rotateAroundUp(entity.position - center, -currentYaw)
        val transform = yawMatrix(-currentYaw).mul(entity.transformation.joml)
        rotationSnapshots[entity.entityId] = RotationSnapshot(entity, relPos, transform)
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

        if (autoFace && ticksAlive % AUTO_FACE_INTERVAL == 0) {
            updateAutoFace()
        }

        updateHoverStates()

        elements.filterIsInstance<SliderElement>().forEach { slider ->
            if (slider.isDragging()) {
                slider.update()
            }
        }

        onTick?.invoke()
    }

    private fun updateHoverStates() {
        val eyePos = owner.eyePosition()
        val lookDir = owner.lookDirection()

        var closestElement: UIElement? = null
        var closestDistance = Double.MAX_VALUE

        for (element in elements) {
            if (!element.isInteractive) continue

            val result = if (element.usesRectangularHitbox()) {
                isLookingAtRectangularElement(eyePos, lookDir, element)
            } else {
                isLookingAtElement(eyePos, lookDir, element)
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

    // --- Auto-facing ---

    private fun updateAutoFace() {
        // Face the nearest player in range (falls back to the owner if in range)
        val candidates = platform.getOnlinePlayers().ifEmpty { listOf(owner) }
        val nearest = candidates.minByOrNull { it.eyePosition().distanceSquared(center) } ?: return
        val toViewer = nearest.eyePosition() - center
        if (toViewer.length() > autoFaceRange) return

        // Panel content faces along -forward, so forward should point from the
        // viewer through the panel: desired forward = center - viewer, projected
        // onto the plane perpendicular to up.
        val toCenter = toViewer * -1.0
        val horizontal = toCenter - up * toCenter.dot(up)
        if (horizontal.lengthSquared() < 1.0e-6) return // directly above/below
        val desired = horizontal.normalize()

        val targetYaw = signedAngleAroundUp(baseForward, desired)
        var delta = wrapAngle(targetYaw - currentYaw)
        if (abs(delta) < YAW_EPSILON) return
        if (abs(delta) > YAW_SNAP) delta *= YAW_LERP // smooth big swings, snap the tail
        currentYaw = wrapAngle(currentYaw + delta)
        applyYaw()
    }

    /** Re-derives the basis and re-poses every entity for [currentYaw]. */
    private fun applyYaw() {
        right = rotateAroundUp(baseRight, currentYaw)
        forward = rotateAroundUp(baseForward, currentYaw)
        transformMatrix = calculateTransformMatrix()

        val rot = yawMatrix(currentYaw)
        val uuids = viewers.toSet()
        for (snapshot in rotationSnapshots.values) {
            val entity = snapshot.entity
            entity.position = center + rotateAroundUp(snapshot.relPos, currentYaw)
            platform.packetSender.teleportEntity(entity, uuids)
            // Billboarded entities rotate client-side; only rigid transforms follow the panel
            if (entity.billboard == Billboard.FIXED) {
                entity.transformation = Mat4f(Matrix4f(rot).mul(snapshot.transformation))
                platform.packetSender.updateMetadata(entity, uuids)
            }
        }
    }

    private fun rotateAroundUp(v: Vec3d, angle: Double): Vec3d {
        // Rodrigues rotation around the (unit) up axis
        val c = cos(angle)
        val s = sin(angle)
        val cross = up.cross(v)
        val dot = up.dot(v)
        return v * c + cross * s + up * (dot * (1.0 - c))
    }

    private fun yawMatrix(angle: Double): Matrix4f =
        Matrix4f().rotate(angle.toFloat(), up.x.toFloat(), up.y.toFloat(), up.z.toFloat())

    private fun signedAngleAroundUp(from: Vec3d, to: Vec3d): Double =
        atan2(from.cross(to).dot(up), from.dot(to))

    private fun wrapAngle(a: Double): Double {
        var r = a
        while (r > PI) r -= 2 * PI
        while (r < -PI) r += 2 * PI
        return r
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

    /**
     * Ray test against the plane the ELEMENT actually lies in (its forward
     * offset), not the UI base plane. Elements floating in front of the panel
     * otherwise get view-angle parallax: the visual and clickable positions
     * diverge the further off-perpendicular the player looks.
     */
    private fun elementPlaneHit(
        eye: Vec3d, direction: Vec3d, element: UIElement
    ): Triple<Double, Double, Double>? {
        val planeNormal = getPlaneNormal()
        val planePoint = localToWorld(0.0, 0.0, element.localOffset.z)
        val planeDistance = rayIntersectsPlane(eye, direction, planePoint, planeNormal) ?: return null
        val hitPoint = eye + direction * planeDistance
        // right/up are orthogonal to forward, so measuring from the UI center
        // is exact regardless of the element's forward offset.
        val relativePoint = hitPoint - center
        return Triple(planeDistance, relativePoint.dot(right), relativePoint.dot(up))
    }

    private fun isLookingAtElement(
        eye: Vec3d, direction: Vec3d, element: UIElement
    ): Pair<Double, Double>? {
        val (planeDistance, localX, localY) = elementPlaneHit(eye, direction, element) ?: return null
        val offsetX = localX - element.localOffset.x
        val offsetY = localY - element.localOffset.y
        val offset = sqrt(offsetX * offsetX + offsetY * offsetY)
        return if (offset < element.hitboxSize * element.hitMargin) Pair(planeDistance, offset) else null
    }

    private fun isLookingAtRectangularElement(
        eye: Vec3d, direction: Vec3d, element: UIElement
    ): Pair<Double, Double>? {
        val (planeDistance, localX, localY) = elementPlaneHit(eye, direction, element) ?: return null

        val halfWidth = element.hitboxWidth / 2 * element.hitMargin
        val halfHeight = element.hitboxHeight / 2 * element.hitMargin
        val offsetX = localX - element.localOffset.x
        val offsetY = localY - element.localOffset.y

        if (abs(offsetX) <= halfWidth && abs(offsetY) <= halfHeight) {
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
     * Add a composited sprite canvas. One entity, however many sprites the
     * canvas contains — unlike [addPanel], which costs an entity per call.
     *
     * @param scale World-unit size of one canvas pixel, applied as the
     *   display's transformation scale (see [SpriteCanvasElement]). A bare
     *   scale of 1 renders at `TextMetrics.PIXEL_SIZE` world units per pixel,
     *   which is normally far larger than intended — callers should derive
     *   this from a target on-screen width.
     */
    fun addSpriteCanvas(
        offsetRight: Double, offsetUp: Double, offsetForward: Double = 0.1,
        canvas: SpriteCanvas, scale: Float
    ): SpriteCanvasElement {
        val element = SpriteCanvasElement(
            ui = this,
            localOffset = Vec3d(offsetRight, offsetUp, offsetForward),
            canvas = canvas,
            scale = scale
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
        gap: Float = 0.05f,
        labelScale: Float = TabsElement.DEFAULT_LABEL_SCALE,
        labelPadding: Float = TabsElement.DEFAULT_TAB_PADDING,
        selectedIndex: Int = 0,
        onTabChange: (Int, TabDefinition) -> Unit = { _, _ -> }
    ): TabsElement {
        val element = TabsElement(
            ui = this, localOffset = Vec3d(offsetRight, offsetUp, offsetForward),
            tabs = tabs, tabWidth = tabWidth, tabHeight = tabHeight,
            gap = gap, labelScale = labelScale, labelPadding = labelPadding,
            selectedIndex = selectedIndex, onTabChange = onTabChange
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
        private const val AUTO_FACE_INTERVAL = 2   // ticks between yaw updates
        private const val YAW_EPSILON = 0.01       // ~0.6°: below this, don't touch entities
        private const val YAW_SNAP = 0.03          // below this, jump straight to target
        private const val YAW_LERP = 0.35          // exponential approach factor

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
