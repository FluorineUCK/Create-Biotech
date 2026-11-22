; x0 <- stock_native_sample x1; x1 -> stock_native_order x0.
; Keep the original public input (fence, head, address, crafts). Never ACK or dispatch.
TYPE View RECORD v ANY
TYPE Request RECORD n NBT
TYPE Item RECORD item TEXT count INTEGER components NBT
TYPE Items LIST Item
TYPE MaybeItems OPTIONAL Items
TYPE Converted RECORD c TEXT i MaybeItems
TYPE MaybeNative OPTIONAL NBT
TYPE NativeCheck RECORD ok BOOLEAN value MaybeNative
REG view View
REG request Request
REG converted Converted
REG order NBT
REG checked NativeCheck
REG optional MaybeNative
REG raw ANY
REG sample ANY
REG input ANY
REG result ANY
REG value ANY
REG head ANY
REG field TEXT
REG ok BOOLEAN
REG status INTEGER
REG one INTEGER
CONST one 1
CONST input null
CONST result {"c":"read_failed"}
ready:
SLEEP one
XBUS.STATUS status x0
LESS_INTEGER ok one status
JFALSE ok ready
XBUS.READ view x0
FIELD sample view v
CONST field "value"
HAS.FIELD ok sample field
JFALSE ok done
FIELD value sample value
FIELD value value components
FIELD input value create_biotech:stock
IS_ERROR ok input
JFALSE ok inspect
CONST result {"c":"component_error"}
JMP done
inspect:
CONST result {"c":"empty"}
CONST field "head"
HAS.FIELD ok input field
JFALSE ok done
FIELD head input head
CONST result {"c":"not_native_order"}
CONST field "order"
HAS.FIELD ok head field
JFALSE ok done
FIELD raw head order
CONST result {"c":"invalid_native_order"}
VALUE.CHECK checked raw
FIELD ok checked ok
JFALSE ok done
FIELD optional checked value
REQUIRE_VALUE order optional
RECORD.MAKE request n order
XBUS.WRITE x1 request
XBUS.READ converted x1
MOV result converted
done:
