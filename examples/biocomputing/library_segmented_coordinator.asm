TYPE row_type RECORD o TEXT d ANY e TEXT l BOOLEAN
TYPE context_type RECORD r row_type s ANY d ANY
TYPE result_type RECORD ok BOOLEAN value ANY
TYPE reading_type RECORD v INTEGER
TYPE Header RECORD volume TEXT entry TEXT expected ANY kind TEXT format TEXT bytes INTEGER
TYPE mutation_type RECORD h Header b NBT
REG kick reading_type
REG mutation mutation_type
REG context context_type
REG tx result_type
REG row row_type
REG i INTEGER
REG one INTEGER
REG seq ANY
REG op TEXT
REG write TEXT
REG result TEXT
REG data ANY
REG reply ANY
REG retained ANY
REG flag BOOLEAN
XBUS.READ kick x1
CONST i 0
CONST one 1
CONST seq 0
CONST write "write"
CONST result "result"
CONST retained null
next:
LIST.GET row commands i
FIELD op row o
FIELD data row d
VALUE.EQUALS flag op write
JFALSE flag dispatch
RECORD.MAKE tx ok flag value retained
XBUS.WRITE x1 tx
XBUS.READ mutation x1
MOV data mutation
dispatch:
RECORD.MAKE context r row s seq d data
XBUS.WRITE x0 context
XBUS.READ tx x0
FIELD flag tx ok
JFALSE flag done
FIELD reply tx value
FIELD seq reply sequence
VALUE.EQUALS flag op result
JFALSE flag advance
FIELD retained reply value
advance:
ADD_INTEGER i i one
FIELD flag row l
JFALSE flag next
RECORD.MAKE tx ok flag value retained
done:
XBUS.WRITE x1 tx
