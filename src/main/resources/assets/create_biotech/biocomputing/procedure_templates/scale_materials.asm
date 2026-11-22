; scale_materials@1: apply to recipe.inputs AND separately each sequenced stage.inputs.
; Pure list transformation only: no resolution, merging, submission or production permission.
TYPE MaybeText OPTIONAL TEXT
TYPE Material RECORD item MaybeText tag MaybeText count INTEGER components NBT
TYPE Materials LIST Material
TYPE MaybeMaterials OPTIONAL Materials
REG result OUTPUT result
REG scaled Materials
REG maybe MaybeMaterials
REG row Material
REG next Material
REG item MaybeText
REG tag MaybeText
REG components NBT
REG count INTEGER
REG zero INTEGER
REG one INTEGER
REG index INTEGER
REG length INTEGER
REG ok BOOLEAN
REG more BOOLEAN
CONST zero 0
CONST one 1
CONST maybe null
CONST ok false
GREATER_INTEGER more batches zero
JFALSE more finish
LIST.MAKE scaled
CONST index 0
LIST.LENGTH length materials
loop:
LESS_INTEGER more index length
JFALSE more success
LIST.GET row materials index
FIELD item row item
FIELD tag row tag
FIELD components row components
FIELD count row count
MULTIPLY_INTEGER count count batches
RECORD.MAKE next item item tag tag count count components components
LIST.APPEND scaled scaled next
ADD_INTEGER index index one
JMP loop
success:
CONST ok true
MOV maybe scaled
finish:
RECORD.MAKE result ok ok materials maybe
RET
