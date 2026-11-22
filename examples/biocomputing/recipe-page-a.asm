TYPE envelope RECORD value ANY
REG forwarded envelope
TYPE target_type RECORD x INTEGER y INTEGER z INTEGER
TYPE packet_type RECORD kind TEXT target target_type request TEXT operation TEXT sequence ANY data ANY
TYPE outcome_type RECORD ok BOOLEAN value ANY
REG i INTEGER
REG seq ANY
REG one INTEGER
REG kind TEXT
REG read TEXT
REG result TEXT
REG busy TEXT
REG row ELEMENT commands
REG packet packet_type
REG tx outcome_type
REG op TEXT
REG data ANY
REG request TEXT
REG reply ANY
REG code ANY
REG expected TEXT
REG flag BOOLEAN
REG last BOOLEAN
CONST i 0
CONST seq 0
CONST one 1
CONST kind "library_command"
CONST read "recipe_page"
CONST result "result"
CONST busy "busy"
prepare:
LIST.GET row commands i
FIELD op row o
FIELD data row d
CORRELATION request
RECORD.MAKE packet kind kind target target request request operation op sequence seq data data
XBUS.WRITE x0 packet
XBUS.READ tx x0
FIELD flag tx ok
FIELD reply tx value
JFALSE flag fail
FIELD seq reply sequence
FIELD code reply code
FIELD expected row e
VALUE.EQUALS flag code expected
JFALSE flag unexpected
VALUE.EQUALS flag code read
JFALSE flag advance
FIELD data reply value
RECORD.MAKE forwarded value data
XBUS.WRITE x1 forwarded
advance:
FIELD last row l
JFALSE last next

JMP done
next:
ADD_INTEGER i i one
JMP prepare
unexpected:
VALUE.EQUALS flag op result
JFALSE flag fail
VALUE.EQUALS flag code busy
JFALSE flag fail
JMP prepare
fail:

done:
