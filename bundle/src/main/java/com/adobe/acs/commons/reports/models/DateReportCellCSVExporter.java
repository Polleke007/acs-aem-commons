/*
 * #%L
 * ACS AEM Commons Bundle
 * %%
 * Copyright (C) 2017 Adobe
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */
package com.adobe.acs.commons.reports.models;

import java.text.SimpleDateFormat;
import java.util.Calendar;

import javax.inject.Inject;

import com.adobe.acs.commons.reports.internal.ExporterUtil;
import org.apache.commons.lang3.StringUtils;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.models.annotations.Model;
import org.apache.sling.models.annotations.Optional;

import com.adobe.acs.commons.reports.api.ReportCellCSVExporter;

/**
 * An exporter for exporting formatted string values
 */
@Model(adaptables = Resource.class)
public class DateReportCellCSVExporter implements ReportCellCSVExporter {

  @Inject
  private String property;

  @Inject
  @Optional
  private String format;

  @Override
  public String getValue(Object result) {
    Resource resource = (Resource) result;

    final String relativePropertyPath = ExporterUtil.relativizePath(property);

    Calendar cal = resource.getValueMap().get(relativePropertyPath, Calendar.class);

    if (cal != null) {
      if (StringUtils.isNotBlank(format)) {
        SimpleDateFormat sdf = new SimpleDateFormat(format);
        return sdf.format(cal.getTime());
      } else {
        return cal.getTime().toString();
      }
    } else {
      return null;
    }
  }
}