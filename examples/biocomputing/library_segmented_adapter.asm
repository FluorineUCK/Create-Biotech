TYPE target_type RECORD x INTEGER y INTEGER z INTEGER
TYPE packet_type RECORD kind TEXT target target_type request TEXT operation TEXT sequence ANY data ANY
TYPE row_type RECORD o TEXT d ANY e TEXT l BOOLEAN
TYPE context_type RECORD r row_type s ANY d ANY
TYPE result_type RECORD ok BOOLEAN value ANY
REG context context_type
REG row row_type
REG seq ANY
REG data ANY
REG op TEXT
REG expected TEXT
REG kind TEXT
REG request TEXT
REG packet packet_type
REG tx result_type
REG reply ANY
REG code ANY
REG result TEXT
REG busy TEXT
REG status TEXT
REG flag BOOLEAN
XBUS.READ context x0
FIELD row context r
FIELD seq context s
FIELD data context d
FIELD op row o
FIELD expected row e
CONST kind "library_command"
CONST result "result"
CONST busy "busy"
CONST status "status"
poll:
CORRELATION request
RECORD.MAKE packet kind kind target target request request operation op sequence seq data data
XBUS.WRITE x1 packet
XBUS.READ tx x1
FIELD flag tx ok
JFALSE flag done
FIELD reply tx value
FIELD code reply code
VALUE.EQUALS flag code expected
JFALSE flag unexpected
JMP reply
unexpected:
VALUE.EQUALS flag op result
JFALSE flag check_status
JMP wait_busy
check_status:
VALUE.EQUALS flag op status
JFALSE flag reply
wait_busy:
VALUE.EQUALS flag code busy
JFALSE flag reply
JMP poll
reply:
RECORD.MAKE tx ok flag value reply
done:
XBUS.WRITE x0 tx
