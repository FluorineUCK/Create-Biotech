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
TYPE Materials LIST Material
TYPE Choices LIST MaybeChoice
TYPE MaybeExact OPTIONAL Exact
TYPE Reply RECORD c TEXT i MaybeExact
TYPE Request RECORD i MaybeText t MaybeText n INTEGER c NBT p MaybeChoice
REG result OUTPUT result
REG row Material
REG option MaybeChoice
REG item MaybeText
REG tag MaybeText
REG count INTEGER
REG components NBT
REG request Request
REG reply Reply
REG exact Exact
REG optional MaybeExact
REG out Items
REG maybe MaybeItems
REG code TEXT
REG accepted TEXT
REG ok BOOLEAN
REG ready BOOLEAN
REG more BOOLEAN
REG n INTEGER
REG m INTEGER
REG i INTEGER
REG zero INTEGER
REG one INTEGER
REG status INTEGER
REG fifteen INTEGER
CONST result {"ok":false,"code":"count_mismatch","items":null}
CONST i 0
CONST zero 0
CONST one 1
CONST fifteen 15
CONST accepted "ok"
LIST.LENGTH n materials
LIST.LENGTH m choices
EQUAL_INTEGER more n m
JFALSE more done
LIST.MAKE out
loop:
LESS_INTEGER more i n
JFALSE more success
LIST.GET row materials i
LIST.GET option choices i
FIELD item row item
FIELD tag row tag
FIELD count row count
FIELD components row components
RECORD.MAKE request i item t tag n count c components p option
XBUS.WRITE x0 request
response:
XBUS.STATUS status x0
EQUAL_INTEGER ready status fifteen
JFALSE ready pause_response
XBUS.READ reply x0
FIELD code reply c
VALUE.EQUALS more code accepted
JFALSE more reject
FIELD optional reply i
REQUIRE_VALUE exact optional
LIST.APPEND out out exact
ADD_INTEGER i i one
JMP loop
success:
CONST ok true
RECORD.MAKE result ok ok code accepted items out
RET
reject:
CONST ok false
CONST maybe null
RECORD.MAKE result ok ok code code items maybe
done:
RET
pause_response:
SLEEP poll_ticks
JMP response
