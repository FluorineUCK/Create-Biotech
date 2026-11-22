; C: publish a labelled literal demo catalog. No creation or dispatch.
TYPE target_type RECORD x INTEGER y INTEGER z INTEGER
TYPE call_type RECORD k BOOLEAN t target_type o TEXT a ANY
TYPE result_type RECORD r ANY
TYPE entry_list LIST ANY
TYPE put_args RECORD device ANY session ANY revision ANY entries entry_list
REG replace put_args
REG call call_type
REG result result_type
REG reply ANY
REG reader BOOLEAN
REG op TEXT
REG args ANY
REG state ANY
REG device ANY
REG session ANY
REG revision ANY
REG code ANY
REG expected TEXT
REG ok BOOLEAN
REG publishing BOOLEAN
CONST reader false
CONST publishing false
CONST op "status"
CONST args {}
CONST expected "ok"
send:
RECORD.MAKE call k reader t output o op a args
XBUS.WRITE x0 call
XBUS.READ result x0
FIELD reply result r
FIELD code reply code
VALUE.EQUALS ok code expected
JFALSE ok done
JFALSE publishing prepare
JMP done
prepare:
FIELD state reply state
FIELD device state device
FIELD session state session
FIELD revision state revision
RECORD.MAKE replace device device session session revision revision entries entries
MOV args replace
CONST op "replace"
CONST expected "accepted"
CONST publishing true
JMP send
done:
