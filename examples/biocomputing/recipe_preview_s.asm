; Preview only. Compact wire/data keys: r=original recipe, n=batches, a=address,
; c=scaled common inputs, s=all scaled stages; each stage i=index,a=address,m=materials.
; V wire o=accepted, r=Optional<RECIPE>, n=batches, w=work rows. Common work i=-1 is NOT stage0.
; V/P run once. S is a persistent service: start S/P/V before A/B/C; pause S explicitly afterward.
; poll_ticks is a player input. Before READ, STATUS must be15; admitted faults are not retried.
TYPE Recipe FORMAT recipe 1
TYPE MaybeRecipe OPTIONAL Recipe
TYPE TextOption OPTIONAL TEXT
TYPE Material RECORD item TextOption tag TextOption count INTEGER components NBT
TYPE Materials LIST Material
TYPE MaybeMaterials OPTIONAL Materials
TYPE Checked RECORD ok BOOLEAN value MaybeRecipe
TYPE Incoming RECORD value ANY
TYPE Work RECORD i INTEGER a ANY m ANY
TYPE WorkList LIST Work
TYPE TypedWork RECORD i INTEGER a ANY m Materials
TYPE TypedWorkList LIST TypedWork
TYPE VRequest RECORD o BOOLEAN r MaybeRecipe n INTEGER w WorkList
TYPE PRequest RECORD o BOOLEAN r MaybeRecipe n INTEGER w TypedWorkList
TYPE Job RECORD m ANY n INTEGER
TYPE SJob RECORD m Materials n INTEGER
TYPE Scaled RECORD ok BOOLEAN materials MaybeMaterials
TYPE ScaleWire RECORD ok BOOLEAN m MaybeMaterials
TYPE Stage RECORD i INTEGER a ANY m Materials
TYPE Stages LIST Stage
TYPE Preview RECORD r Recipe n INTEGER a ANY c Materials s Stages
TYPE MaybePreview OPTIONAL Preview
TYPE Result RECORD ok BOOLEAN value MaybePreview
REG result Scaled
REG scaled Materials
REG maybe MaybeMaterials
REG row Material
REG next Material
REG item TextOption
REG tag TextOption
REG components NBT
REG count INTEGER
REG zero INTEGER
REG one INTEGER
REG index INTEGER
REG length INTEGER
REG ok BOOLEAN
REG more BOOLEAN
REG status INTEGER
REG fifteen INTEGER
REG ready BOOLEAN
REG materials Materials
REG batches INTEGER
REG request SJob
REG wire ScaleWire
REG last BOOLEAN
CONST fifteen 15
receive:
XBUS.STATUS status x0
EQUAL_INTEGER ready status fifteen
JFALSE ready pause_receive
XBUS.READ request x0
FIELD materials request m
FIELD batches request n
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
RECORD.MAKE wire ok ok m maybe
XBUS.WRITE x0 wire
JMP receive
pause_receive:
SLEEP poll_ticks
JMP receive
