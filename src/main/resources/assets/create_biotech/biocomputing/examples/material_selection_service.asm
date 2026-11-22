; Explicit two-Computer selection application. No automatic wiring, lookup, choice or dispatch.
; Wire coordinator x0 <-> service x0. Start service first; pause it explicitly after use.
; poll_ticks is player supplied. STATUS15/SLEEP precedes every READ; admitted faults are not retried.
; Request: i=item?,t=tag?,n=count,c=native components,p=explicit optional reader Choice {slot,item,tags}.
; Reply: c=business code ("ok" only for success),i=Optional exact {item,count,components}; not authority.
; Coordinator alone publishes {ok,code,items} after ALL rows; failures never publish a partial list.
; Complete explicit selection prototype; no world access, quantity inference or merge.
TYPE MaybeText OPTIONAL TEXT
TYPE Material RECORD item MaybeText tag MaybeText count INTEGER components NBT
TYPE Tags LIST TEXT
TYPE Choice RECORD slot INTEGER item TEXT tags Tags
TYPE MaybeChoice OPTIONAL Choice
TYPE Exact RECORD item TEXT count INTEGER components NBT
TYPE Items LIST Exact
TYPE MaybeItems OPTIONAL Items
TYPE MaybeExact OPTIONAL Exact
TYPE Reply RECORD c TEXT i MaybeExact
REG result Reply
REG picked Choice
REG item MaybeText
REG tag MaybeText
REG count INTEGER
REG components NBT
REG selected TEXT
REG wanted TEXT
REG tags Tags
REG actual TEXT
REG out Items
REG maybe MaybeItems
REG exact Exact
REG n INTEGER
REG m INTEGER
REG i INTEGER
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
TYPE Request RECORD i MaybeText t MaybeText n INTEGER c NBT p MaybeChoice
REG request Request
REG status INTEGER
REG fifteen INTEGER
REG ready BOOLEAN
REG option MaybeChoice
CONST fifteen 15
receive:
XBUS.STATUS status x0
EQUAL_INTEGER ready status fifteen
JFALSE ready pause_receive
XBUS.READ request x0
FIELD item request i
FIELD tag request t
FIELD count request n
FIELD components request c
FIELD option request p
CONST zero 0
CONST one 1
CONST air "minecraft:air"
CONST result {"c":"invalid_material","i":null}
GREATER_INTEGER valid count zero
JFALSE valid done
IS_NULL item_null item
IS_NULL tag_null tag
XOR_BOOLEAN valid item_null tag_null
JFALSE valid done
IS_NULL no_choice option
JFALSE no_choice chosen
JFALSE item_null exact_without_choice
CONST result {"c":"selection_required","i":null}
JMP reply
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
CONST code "ok"
RECORD.MAKE result c code i exact
JMP reply
tag_mismatch:
CONST result {"c":"tag_mismatch","i":null}
JMP reply
choice_mismatch:
CONST result {"c":"choice_mismatch","i":null}
done:
reply:
XBUS.WRITE x0 result
JMP receive
pause_receive:
SLEEP poll_ticks
JMP receive
