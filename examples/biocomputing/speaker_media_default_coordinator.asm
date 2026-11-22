; x0 -> Library stage; x1 -> Speaker delivery stage.
; Rows describe the visible order of public operations; no dynamic Java driver.
TYPE Row RECORD o TEXT s BOOLEAN a INTEGER e TEXT c BOOLEAN z BOOLEAN
TYPE Rows LIST Row
TYPE Values LIST ANY
TYPE Job RECORD o TEXT d ANY p BOOLEAN
TYPE Reply RECORD r ANY
REG values Values
REG row Row
REG job Job
REG received Reply
REG stage TEXT
REG reply ANY
REG speaker_reply ANY
REG data ANY
REG code ANY
REG expected TEXT
REG poll BOOLEAN
REG flag BOOLEAN
REG argument INTEGER
REG index INTEGER
REG size INTEGER
CONST index 0
CONST stage ""
CONST reply null
CONST speaker_reply null
LIST.MAKE values nothing track slot entry nothing
LIST.LENGTH size rows
next:
LESS_INTEGER flag index size
JFALSE flag done
LIST.GET row rows index
FIELD stage row o
FIELD argument row a
LIST.GET data values argument
FIELD poll row z
RECORD.MAKE job o stage d data p poll
FIELD flag row s
JFALSE flag library
XBUS.WRITE x1 job
XBUS.READ received x1
FIELD speaker_reply received r
JMP check
library:
XBUS.WRITE x0 job
XBUS.READ received x0
check:
FIELD reply received r
FIELD code reply code
FIELD expected row e
VALUE.EQUALS flag code expected
JFALSE flag done
FIELD flag row c
JFALSE flag advance
FIELD data reply value
LIST.REPLACE values values reference_index data
advance:
ADD_INTEGER index index one
JMP next
done:
