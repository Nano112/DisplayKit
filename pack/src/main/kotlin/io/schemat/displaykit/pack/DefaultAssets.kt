package io.schemat.displaykit.pack

/**
 * Default shaders and assets for DisplayKit.
 *
 * MC 1.21.11 shader system:
 * - #version 330 with UBO-based uniforms via #moj_import
 * - No .json shader definition files (vertex format defined in code)
 * - Post chains at post_effect/ in new format
 * - Core shaders at shaders/core/ (.vsh + .fsh only)
 */
object DefaultAssets : AssetProvider {

    override fun contributeAssets(builder: PackBuilder) {
        // Rounded corners via SDF on text_display backgrounds
        addRoundedCornersShader(builder)

        // Hide glow outline on trigger entities (rendertype_outline → transparent)
        addTransparentOutline(builder)

        // Glass blur via entity_outline post chain override
        addGlassPostChain(builder)
    }

    private fun addRoundedCornersShader(builder: PackBuilder) {
        // TEST: Exact copy of vanilla VSH — no changes at all.
        builder.addMinecraftShader("core", "rendertype_text_background", "vsh", """
            |#version 330
            |
            |#moj_import <minecraft:fog.glsl>
            |#moj_import <minecraft:dynamictransforms.glsl>
            |#moj_import <minecraft:projection.glsl>
            |
            |in vec3 Position;
            |in vec4 Color;
            |in ivec2 UV2;
            |
            |uniform sampler2D Sampler2;
            |
            |out float sphericalVertexDistance;
            |out float cylindricalVertexDistance;
            |out vec4 vertexColor;
            |
            |void main() {
            |    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
            |
            |    sphericalVertexDistance = fog_spherical_distance(Position);
            |    cylindricalVertexDistance = fog_cylindrical_distance(Position);
            |    vertexColor = Color * texelFetch(Sampler2, UV2 / 16, 0);
            |}
        """.trimMargin())

        // TEST: Exact copy of vanilla FSH but force bright red color.
        // If you see red backgrounds → shader override works.
        // If backgrounds look normal → shader override is broken.
        builder.addMinecraftShader("core", "rendertype_text_background", "fsh", """
            |#version 330
            |
            |#moj_import <minecraft:fog.glsl>
            |#moj_import <minecraft:dynamictransforms.glsl>
            |
            |uniform sampler2D Sampler0;
            |
            |in float sphericalVertexDistance;
            |in float cylindricalVertexDistance;
            |in vec4 vertexColor;
            |in vec2 texCoord0;
            |
            |out vec4 fragColor;
            |
            |void main() {
            |    vec4 color = vec4(1.0, 0.0, 0.0, 1.0);
            |    fragColor = color;
            |}
        """.trimMargin())
    }

    private fun addTransparentOutline(builder: PackBuilder) {
        // Override rendertype_outline — the shader MC uses to render entity glow
        // silhouettes to the entity_outline framebuffer.
        // Output transparent so no glow silhouette is written.
        // Vertex format: Position (vec3), Color (vec4), UV0 (vec2).

        builder.addMinecraftShader("core", "rendertype_outline", "vsh", """
            |#version 330
            |
            |#moj_import <minecraft:dynamictransforms.glsl>
            |#moj_import <minecraft:projection.glsl>
            |
            |in vec3 Position;
            |in vec4 Color;
            |in vec2 UV0;
            |
            |out vec4 vertexColor;
            |out vec2 texCoord0;
            |
            |void main() {
            |    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
            |    vertexColor = Color;
            |    texCoord0 = UV0;
            |}
        """.trimMargin())

        builder.addMinecraftShader("core", "rendertype_outline", "fsh", """
            |#version 330
            |
            |#moj_import <minecraft:dynamictransforms.glsl>
            |
            |uniform sampler2D Sampler0;
            |
            |in vec4 vertexColor;
            |in vec2 texCoord0;
            |
            |out vec4 fragColor;
            |
            |void main() {
            |    vec4 color = texture(Sampler0, texCoord0);
            |    if (color.a == 0.0) {
            |        discard;
            |    }
            |    // Output zero alpha — no glow silhouette written to entity_outline buffer.
            |    // The entity_outline post chain still activates from the glowing entity.
            |    fragColor = vec4(0.0);
            |}
        """.trimMargin())
    }

    private fun addGlassPostChain(builder: PackBuilder) {
        // Override entity_outline post-processing chain (MC 1.21.11 format).
        // Normally: Sobel edge detection → blur → composite glow outline.
        // Ours: blur main framebuffer + clear entity_outline buffer.
        //
        // Pass 1: H-blur main → swap
        // Pass 2: V-blur swap → main
        // Pass 3: Blit zeros → entity_outline (prevents glow compositing)
        //
        // Reuses MC's built-in screenquad.vsh, entity_outline_box_blur.fsh, and blit.fsh.
        builder.addJson("assets/minecraft/post_effect/entity_outline.json", """
            |{
            |    "targets": {
            |        "swap": {}
            |    },
            |    "passes": [
            |        {
            |            "vertex_shader": "minecraft:core/screenquad",
            |            "fragment_shader": "minecraft:post/entity_outline_box_blur",
            |            "inputs": [
            |                {
            |                    "sampler_name": "In",
            |                    "target": "minecraft:main"
            |                }
            |            ],
            |            "output": "swap",
            |            "uniforms": {
            |                "BlurConfig": [
            |                    { "name": "BlurDir", "type": "vec2", "value": [ 1.0, 0.0 ] },
            |                    { "name": "Radius", "type": "float", "value": 2.0 }
            |                ]
            |            }
            |        },
            |        {
            |            "vertex_shader": "minecraft:core/screenquad",
            |            "fragment_shader": "minecraft:post/entity_outline_box_blur",
            |            "inputs": [
            |                {
            |                    "sampler_name": "In",
            |                    "target": "swap"
            |                }
            |            ],
            |            "output": "minecraft:main",
            |            "uniforms": {
            |                "BlurConfig": [
            |                    { "name": "BlurDir", "type": "vec2", "value": [ 0.0, 1.0 ] },
            |                    { "name": "Radius", "type": "float", "value": 2.0 }
            |                ]
            |            }
            |        },
            |        {
            |            "vertex_shader": "minecraft:core/screenquad",
            |            "fragment_shader": "minecraft:post/blit",
            |            "inputs": [
            |                {
            |                    "sampler_name": "In",
            |                    "target": "swap"
            |                }
            |            ],
            |            "output": "minecraft:entity_outline",
            |            "uniforms": {
            |                "BlitConfig": [
            |                    { "name": "ColorModulate", "type": "vec4", "value": [ 0.0, 0.0, 0.0, 0.0 ] }
            |                ]
            |            }
            |        }
            |    ]
            |}
        """.trimMargin())
    }
}
