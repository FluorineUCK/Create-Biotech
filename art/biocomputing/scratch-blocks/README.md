# Scratch Blocks artwork in Biocomputing

The block silhouettes come from SVG paths rendered by the public Scratch project editor
(`https://scratch.mit.edu/projects/editor/`, captured 2026-11-11). `source.json` retains the observed paths,
bounds and colors for the relevant block kinds. No logos, character art, sounds, web bundles or page
scripts are shipped.

Upstream: [Scratch Blocks](https://github.com/scratchfoundation/scratch-blocks) (Scratch Foundation/MIT,
with the Blockly renderer from Google and contributors), Apache-2.0. A copy of the upstream license
ships in `META-INF/licenses/scratch-blocks.txt` in the mod JAR.

Modifications: the selected paths are cropped, scaled and recolored to create one reusable texture.
The procedure definition (bowler hat) and its inset prototype were captured from a temporary custom
block with two Boolean parameters. The bake compacts only the long horizontal spans of those two
paths; their cap curves and connectors remain the captured Scratch shapes. The inset uses the
original darker prototype fill. The C-shaped repeat block is sliced into its head, side and foot. The inner SVG segment is translated
to match the mod's existing child indentation without changing its curves or hit targets. The native editor stretches straight
sections while retaining the captured end shapes, then draws the mod's own localized labels. Storage,
data, communication and output borrow Scratch colors; this does not import their original behaviors.
The middle bar for a two-body conditional is cropped from the official editor's rendered `control_if_else`
path. It retains the first cavity's inner closing corner and the second cavity's opening connector.
It does not reuse the top of a statement block: the left spine remains continuous through “else”.
The terminal control shape retains the captured stack's upper connector and corner curves;
its lower tab is replaced with a straight edge to represent the absent next connection.

The source and the baked SVG are retained here for inspection. To regenerate the local PNG, run
`node art/biocomputing/scratch-blocks/bake.mjs` with Playwright and Chrome available. An absolute path to
the local Playwright module can be passed as the first argument. Baking is not a build or runtime
dependency. The actual game texture is `assets/create_biotech/textures/gui/biocomputing/scratch_blocks.png`.
