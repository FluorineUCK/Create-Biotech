TYPE R FORMAT recipe 1
TYPE maybe_recipe OPTIONAL R
TYPE recipe_check RECORD ok BOOLEAN value maybe_recipe
TYPE maybe_integer OPTIONAL INTEGER
TYPE integer_check RECORD ok BOOLEAN value maybe_integer
TYPE calculation_type RECORD recipe R ingredient ANY batches INTEGER count INTEGER
TYPE envelope RECORD value ANY
REG incoming envelope
REG page ANY
REG recipe R
REG selected_recipe maybe_recipe
REG validated recipe_check
REG ingredients ANY
REG ingredient ANY
REG raw_count ANY
REG count INTEGER
REG selected maybe_integer
REG checked integer_check
REG flag BOOLEAN
REG result ANY
REG calculated calculation_type
REG status INTEGER
REG ready INTEGER
CONST ready 15
poll:
XBUS.STATUS status x0
EQUAL_INTEGER flag status ready
JFALSE flag yield
JMP receive
yield:
SLEEP poll_ticks
JMP poll
receive:
XBUS.READ incoming x0
FIELD page incoming value
FMT.CHECK validated page recipe 1
MOV result validated
FIELD flag validated ok
JFALSE flag done
FIELD selected_recipe validated value
REQUIRE_VALUE recipe selected_recipe
FMT.PROJECT page recipe recipe 1
FIELD ingredients page inputs
LIST.GET ingredient ingredients ingredient_index
FIELD raw_count ingredient count
VALUE.CHECK checked raw_count
MOV result checked
FIELD flag checked ok
JFALSE flag done
FIELD selected checked value
REQUIRE_VALUE count selected
MULTIPLY_INTEGER count count batches
RECORD.MAKE calculated recipe recipe ingredient ingredient batches batches count count
MOV result calculated
done:
