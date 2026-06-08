/**
 * Copyright (C) 2015  the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */ 


package mujava.util;

import java.io.FilenameFilter;
import java.io.File;

/**
 * <p>Description: </p>
 * @author Yu-Seung Ma
 * @version 1.0
  */

public class ExtensionFilter implements FilenameFilter {
  private String mask;

  public ExtensionFilter() {
  }

  public ExtensionFilter(String str) {
    this.mask = str;
  }

  @Override
  public boolean accept(File dir, String name) {
    // 忽略带 $ 的类（通常是内部类），你保留了这个逻辑
    if (name.contains("$")) return false;

    if (mask != null) {
      int index = name.lastIndexOf(".");
      // ✅ 确保有扩展名并且不是最后一位
      if (index > 0 && index < name.length() - 1) {
        String ext = name.substring(index + 1);
        return ext.equals(mask);
      } else {
        return false; // 没有扩展名，直接过滤掉
      }
    }

    // 如果没设置 mask，就不过滤
    return true;
  }
}

