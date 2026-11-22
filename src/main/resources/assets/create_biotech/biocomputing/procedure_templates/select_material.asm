; Pure select_material@1. Inputs item/tag/count/components/option are explicit data copies.
; Choice is the reader descriptor {slot,item,tags}; slot grants no world access.
; No quantity inference, lookup, merge, authorization or dispatch. Requires ISA-1a, v6.
TYPE MaybeText OPTIONAL TEXT
TYPE Material RECORD item MaybeText tag MaybeText count INTEGER components NBT
TYPE Tags LIST TEXT
TYPE Choice RECORD slot INTEGER item TEXT tags Tags
TYPE MaybeChoice OPTIONAL Choice
TYPE Exact RECORD item TEXT count INTEGER components NBT
TYPE Items LIST Exact
TYPE MaybeItems OPTIONAL Items
REG result OUTPUT result
REG picked Choice
REG selected TEXT
REG tags Tags
REG actual TEXT
REG exact Exact
REG k INTEGER
REG length INTEGER
REG one INTEGER
REG zero INTEGER
REG air TEXT
REG valid BOOLEAN
REG item_null BOOLEAN
REG tag_null BOOLEAN
REG no_choice BOOLEAN
REG code TEXT
REG ok BOOLEAN
CONST zero 0
CONST one 1
CONST air "minecraft:air"
CONST result {"ok":false,"code":"invalid_material","item":null}
GREATER_INTEGER valid count zero
JFALSE valid done
IS_NULL item_null item
IS_NULL tag_null tag
XOR_BOOLEAN valid item_null tag_null
JFALSE valid done
IS_NULL no_choice option
JFALSE no_choice chosen
JFALSE item_null exact_without_choice
CONST result {"ok":false,"code":"selection_required","item":null}
RET
exact_without_choice:
REQUIRE_VALUE selected item
JMP selected
chosen:
REQUIRE_VALUE picked option
FIELD selected picked item
JFALSE item_null exact_choice
FIELD tags picked tags
LIST.LENGTH length tags
CONST k 0
tag_loop:
LESS_INTEGER valid k length
JFALSE valid tag_mismatch
LIST.GET actual tags k
VALUE.EQUALS valid tag actual
ADD_INTEGER k k one
JFALSE valid tag_loop
JMP selected
exact_choice:
VALUE.EQUALS valid item selected
JFALSE valid choice_mismatch
selected:
TEXT.LENGTH length selected
GREATER_INTEGER valid length zero
JFALSE valid done
VALUE.EQUALS valid selected air
JFALSE valid append
JMP done
append:
RECORD.MAKE exact item selected count count components components
success:
CONST ok true
CONST code "ok"
RECORD.MAKE result ok ok code code item exact
RET
tag_mismatch:
CONST result {"ok":false,"code":"tag_mismatch","item":null}
RET
choice_mismatch:
CONST result {"ok":false,"code":"choice_mismatch","item":null}
done:
RET
