/*
 * Copyright (C) 2017-2019 Dremio Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
import Button from '@app/components/Buttons/Button';
import * as ButtonTypes from '@app/components/Buttons/ButtonTypes';
import EngineStatus from '@app/pages/AdminPage/subpages/Provisioning/components/EngineStatus';

export default function(input) {
  Object.assign(input.prototype, { // eslint-disable-line no-restricted-properties
    getEngineStatus(engine, styles) {
      return <EngineStatus engine={engine} style={styles.statusIcon} />;
    },

    // eslint-disable-next-line @typescript-eslint/no-unused-vars
    renderDescription(engine) {
      return null;
    },

    renderButtons(onEdit, isReadOnly) {
      return (
        <Button
          style={styles.edit}
          onClick={this.onEdit}
          disable={isReadOnly}
          type={ButtonTypes.NEXT}
          text={la('Edit Settings')}
        />
      );
    }
  });
}

const styles = {
  edit: {
    width: 100,
    marginTop: 5
  }
};
