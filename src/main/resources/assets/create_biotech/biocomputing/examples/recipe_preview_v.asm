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
REG incoming Incoming
REG raw ANY
REG checked Checked
REG ok BOOLEAN
REG recipe MaybeRecipe
REG materials ANY
REG body ANY
REG stages ANY
REG address ANY
REG key TEXT
REG many BOOLEAN
REG zero INTEGER
REG one INTEGER
REG i INTEGER
REG length INTEGER
REG more BOOLEAN
REG row Work
REG work WorkList
REG result VRequest
CONST fifteen 15
CONST zero 0
CONST one 1
CONST i -1
CONST key "stages"
CONST stages []
CONST address null
LIST.MAKE work
receive:
XBUS.STATUS status x0
EQUAL_INTEGER ready status fifteen
JFALSE ready pause_receive
XBUS.READ incoming x0
FIELD raw incoming value
FMT.CHECK checked raw recipe 1
FIELD ok checked ok
FIELD recipe checked value
JFALSE ok send
GREATER_INTEGER ok batches zero
JFALSE ok send
FMT.PROJECT raw recipe recipe 1
FIELD materials raw inputs
FIELD body raw body
HAS.FIELD many body key
JFALSE many standard
FIELD stages body stages
JMP common
standard:
FIELD address body address
common:
LIST.LENGTH length stages
loop:
RECORD.MAKE row i i a address m materials
LIST.APPEND work work row
ADD_INTEGER i i one
LESS_INTEGER more i length
JFALSE more send
LIST.GET raw stages i
FIELD materials raw inputs
FIELD address raw address
JMP loop
send:
RECORD.MAKE result o ok r recipe n batches w work
XBUS.WRITE x1 result
RET
pause_receive:
SLEEP poll_ticks
JMP receive
