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
REG status INTEGER
REG fifteen INTEGER
REG ready BOOLEAN
REG incoming PRequest
REG ok BOOLEAN
REG accepted BOOLEAN
REG maybe MaybePreview
REG recipe_option MaybeRecipe
REG recipe Recipe
REG batches INTEGER
REG work TypedWorkList
REG row TypedWork
REG materials Materials
REG address ANY
REG top_address ANY
REG one INTEGER
REG zero INTEGER
REG i INTEGER
REG index INTEGER
REG next INTEGER
REG length INTEGER
REG more BOOLEAN
REG last BOOLEAN
REG common BOOLEAN
REG job Job
REG reply ScaleWire
REG optional MaybeMaterials
REG scaled Materials
REG common_scaled Materials
REG stage Stage
REG stage_results Stages
REG preview Preview
REG result Result
CONST fifteen 15
CONST one 1
CONST zero 0
CONST i 0
CONST ok false
CONST maybe null
CONST top_address null
LIST.MAKE stage_results
LIST.MAKE common_scaled
receive:
XBUS.STATUS status x0
EQUAL_INTEGER ready status fifteen
JFALSE ready pause_receive
XBUS.READ incoming x0
FIELD accepted incoming o
JFALSE accepted finish
FIELD recipe_option incoming r
REQUIRE_VALUE recipe recipe_option
FIELD batches incoming n
FIELD work incoming w
LIST.LENGTH length work
loop:
LIST.GET row work i
FIELD materials row m
FIELD index row i
FIELD address row a
ADD_INTEGER next i one
LESS_INTEGER more next length
RECORD.MAKE job m materials n batches
XBUS.WRITE x1 job
response:
XBUS.STATUS status x1
EQUAL_INTEGER ready status fifteen
JFALSE ready pause_response
XBUS.READ reply x1
FIELD optional reply m
REQUIRE_VALUE scaled optional
LESS_INTEGER common index zero
JFALSE common append_stage
MOV common_scaled scaled
MOV top_address address
JMP advance
append_stage:
RECORD.MAKE stage i index a address m scaled
LIST.APPEND stage_results stage_results stage
advance:
MOV i next
JFALSE more success
JMP loop
success:
RECORD.MAKE preview r recipe n batches a top_address c common_scaled s stage_results
MOV maybe preview
CONST ok true
finish:
RECORD.MAKE result ok ok value maybe
RET
pause_receive:
SLEEP poll_ticks
JMP receive
pause_response:
SLEEP poll_ticks
JMP response
