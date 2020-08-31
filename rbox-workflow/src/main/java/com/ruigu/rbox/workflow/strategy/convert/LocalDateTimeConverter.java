package com.ruigu.rbox.workflow.strategy.convert;

import com.alibaba.excel.converters.Converter;
import com.alibaba.excel.enums.CellDataTypeEnum;
import com.alibaba.excel.metadata.CellData;
import com.alibaba.excel.metadata.GlobalConfiguration;
import com.alibaba.excel.metadata.property.ExcelContentProperty;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 *
 * @Author panjianwei
 */
public class LocalDateTimeConverter implements Converter<LocalDateTime> {

	@Override
	public Class<LocalDateTime> supportJavaTypeKey() {
		return LocalDateTime.class;
	}

	@Override
	public CellDataTypeEnum supportExcelTypeKey() {
		return CellDataTypeEnum.STRING;
	}

	@Override
	public LocalDateTime convertToJavaData(CellData cellData, ExcelContentProperty contentProperty,
										   GlobalConfiguration globalConfiguration) {
		return LocalDateTime.parse(cellData.getStringValue(), DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
	}

	@Override
	public CellData<String> convertToExcelData(LocalDateTime value, ExcelContentProperty contentProperty,
	                                           GlobalConfiguration globalConfiguration) {
		return new CellData<>(value.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
	}

}
